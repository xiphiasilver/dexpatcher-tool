/*
 * DexPatcher - Copyright 2015-2020 Rodrigo Balerdi
 * (GNU General Public License version 3 or later)
 *
 * DexPatcher is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 */

package lanchon.dexpatcher;

import java.io.File;
import java.io.IOException;
import java.util.Locale;

import lanchon.dexpatcher.core.Context;
import lanchon.dexpatcher.core.DexPatcher;
import lanchon.dexpatcher.core.logger.Logger;
import lanchon.dexpatcher.core.util.TemplateMapFileWriter;
import lanchon.dexpatcher.transform.TransformLogger;
import lanchon.dexpatcher.transform.anonymizer.DexAnonymizer;
import lanchon.dexpatcher.transform.anonymizer.TypeAnonymizer;
import lanchon.dexpatcher.transform.codec.decoder.DexDecoder;
import lanchon.dexpatcher.transform.codec.decoder.StringDecoder;
import lanchon.dexpatcher.transform.codec.encoder.BasicDexEncoder;
import lanchon.dexpatcher.transform.codec.encoder.BasicStringEncoder;
import lanchon.dexpatcher.transform.codec.encoder.EncoderConfiguration;
import lanchon.dexpatcher.transform.codec.encoder.EncoderDexMap;
import lanchon.dexpatcher.transform.mapper.DexMapperModule;
import lanchon.dexpatcher.transform.mapper.PatchRewriterModule;
import lanchon.dexpatcher.transform.mapper.map.DexMap;
import lanchon.dexpatcher.transform.mapper.map.LoggingDexMap;
import lanchon.dexpatcher.transform.mapper.map.builder.DexMapping;
import lanchon.dexpatcher.transform.util.DexVisitor;
import lanchon.multidexlib2.BasicDexFileNamer;
import lanchon.multidexlib2.DexFileNamer;
import lanchon.multidexlib2.DexIO;
import lanchon.multidexlib2.MultiDexIO;
import lanchon.multidexlib2.OpcodeUtils;
import lanchon.multidexlib2.SingletonDexContainer;

import org.jf.dexlib2.Opcodes;
import org.jf.dexlib2.iface.DexFile;
import org.jf.dexlib2.rewriter.DexRewriter;
import org.jf.dexlib2.rewriter.RewriterModule;

import static lanchon.dexpatcher.core.logger.Logger.Level.*;

public class Processor {

	static final boolean ABORT_ON_EARLY_ERRORS = true;

	public enum PreTransform {

		NONE,
		DRY,
		OUT,
		INOUT,
		ALL;

		public static PreTransform parse(String s) {
			s = s /* .replace('-', '_') */ .toUpperCase(Locale.ROOT);
			try {
				return PreTransform.valueOf(s);
			} catch (IllegalArgumentException e) {
				return null;
			}
		}

		public String format() {
			return name().toLowerCase(Locale.ROOT) /* .replace('_', '-') */ ;
		}

	}

	public static final PreTransform DEFAULT_PRE_TRANSFORM = PreTransform.OUT;

	public static boolean processFiles(Logger logger, Configuration config) throws IOException {
		return new Processor(logger, config).processFiles();
	}

	private static DexFile transformDex(DexFile dex, RewriterModule module) {
		return new DexRewriter(module).rewriteDexFile(dex);
	}

	private final Logger logger;
	private final Configuration config;

	private DexFileNamer dexFileNamer;
	private Opcodes opcodes;
	private DexMapping directMap;
	private DexMapping inverseMap;
	private DexMapping encodeMap;
	private StringDecoder stringDecoder;

	private Processor(Logger logger, Configuration config) {
		this.logger = logger;
		this.config = config;
	}

	private boolean processFiles() throws IOException {

		long time = System.nanoTime();
		int types = 0;

		logger.setLogLevel(config.logLevel);
		dexFileNamer = new BasicDexFileNamer();
		if (config.apiLevel > 0) opcodes = Opcodes.forApi(config.apiLevel);
		stringDecoder = new StringDecoder(config.codeMarker);
		configureMaps();

		if (logger.hasNotLoggedErrors() || !ABORT_ON_EARLY_ERRORS) {

			TransformLogger outputLogger = new TransformLogger(logger);
			boolean preTransformInputs = config.preTransform == PreTransform.INOUT;
			String mainPlan = config.mainAnonymizationPlan;
			String altPlan = config.alternateAnonymizationPlan;

			DexFile dex = readDex(new File(config.sourceFile));
			TransformLogger sourceLogger = outputLogger.cloneIf(preTransformInputs);
			dex = mapDex(dex, config.mapSource, directMap, false, sourceLogger, "map source");
			dex = anonymizeDex(dex, config.deanonSource || config.deanonSourceAlternate,
					config.deanonSourceAlternate ? altPlan : mainPlan, false, sourceLogger, "deanonymize source");
			dex = encodeDex(dex, config.encodeSource, encodeMap, config.encoderConfiguration, sourceLogger,
					"encode source");
			dex = decodeDex(dex, config.decodeSource, sourceLogger, "decode source");
			dex = anonymizeDex(dex, config.reanonSource, mainPlan, true, sourceLogger, "reanonymize source");
			dex = mapDex(dex, config.unmapSource, inverseMap, true, sourceLogger, "unmap source");
			if (preTransformInputs) preTransformDex(dex, sourceLogger, "transform source");
			types += dex.getClasses().size();

			for (String patchFile : config.patchFiles) {
				DexFile patchDex = readDex(new File(patchFile));
				TransformLogger patchLogger = outputLogger.cloneIf(preTransformInputs);
				patchDex = anonymizeDex(patchDex, config.deanonPatches || config.deanonPatchesAlternate,
						config.deanonPatchesAlternate ? altPlan : mainPlan, false, patchLogger, "deanonymize patch");
				patchDex = decodeDex(patchDex, config.decodePatches, patchLogger, "decode patch");
				patchDex = anonymizeDex(patchDex, config.reanonPatches, mainPlan, true, patchLogger, "reanonymize patch");
				patchDex = mapDex(patchDex, config.unmapPatches, inverseMap, true, patchLogger, "unmap patch");
				if (preTransformInputs) preTransformDex(patchDex, patchLogger, "transform patch");
				types += patchDex.getClasses().size();
				dex = patchDex(dex, patchDex);
			}

			dex = decodeDex(dex, config.decodeOutput, outputLogger, "decode output");
			dex = anonymizeDex(dex, config.reanonOutput, mainPlan, true, outputLogger, "reanonymize output");
			dex = mapDex(dex, config.unmapOutput, inverseMap, true, outputLogger, "unmap output");

			boolean writeDex = logger.hasNotLoggedErrors() && !config.dryRun && config.patchedFile != null;
			boolean preTransformOutput = (config.preTransform == PreTransform.DRY && !writeDex) ||
					config.preTransform == PreTransform.OUT || config.preTransform == PreTransform.INOUT;
			if (preTransformOutput) preTransformDex(dex, outputLogger, "transform output");

			if (logger.hasNotLoggedErrors()) {
				if (config.dryRun) {
					logger.log(INFO, "dry run due to '--dry-run' option");
				} else {
					if (config.patchedFile == null && config.templateMapFile == null) {
						logger.log(WARN, "dry run due to missing '--output' and '--create-map' options");
					} else {
						if (config.patchedFile != null) {
							outputLogger.setSync(config.multiDex && config.multiDexJobs != 1);
							writeDex(new File(config.patchedFile), dex);
						}
						if (config.templateMapFile != null) {
							TemplateMapFileWriter.write(new File(config.templateMapFile), dex, "#");
						}
					}
				}
			}

		} else {
			logger.log(FATAL, "aborting due to errors in setup phase");
		}

		time = System.nanoTime() - time;
		logStats("total process", types, time);

		logger.logErrorAndWarningCounts();
		return logger.hasNotLoggedErrors();

	}

	private void configureMaps() throws IOException {
		directMap = (config.mapSource) ? new DexMapping() : null;
		inverseMap = (config.unmapSource || config.unmapPatches || config.unmapOutput) ? new DexMapping() : null;
		if (directMap != null || inverseMap != null) {
			DexMapping inverseComposeMap = (config.composeMapFiles != null) ? new DexMapping() : null;
			MapReader.readMapPair(config.composeMapFiles, null, config.invertComposeMap, null, inverseComposeMap,
					logger);
			MapReader.readMapPair(config.mapFiles, inverseComposeMap, config.invertMap, directMap, inverseMap, logger);
		}
		encodeMap = (config.encodeSource && config.encodeMapFiles != null) ? new DexMapping() : null;
		MapReader.readMapPair(config.encodeMapFiles, null, config.invertEncodeMap, encodeMap, null, logger);
	}

	private DexFile mapDex(DexFile dex, boolean enabled, DexMap dexMap, boolean isInverseMap, TransformLogger logger,
			String logPrefix) {
		if (enabled) {
			boolean preTransformAll = config.preTransform == PreTransform.ALL;
			TransformLogger privateLogger = logger.cloneIf(preTransformAll);
			DexMap loggingDexMap = new LoggingDexMap(dexMap, isInverseMap, privateLogger, logPrefix, DEBUG);
			RewriterModule mapper = new DexMapperModule(loggingDexMap);
			mapper = PatchRewriterModule.of(mapper, config.annotationPackage);
			dex = transformDex(dex, mapper);
			if (preTransformAll) preTransformDex(dex, privateLogger, logPrefix);
		}
		return dex;
	}

	private DexFile anonymizeDex(DexFile dex, boolean enabled, String plan, boolean reanonymize, TransformLogger logger,
			String logPrefix) {
		if (enabled) {
			boolean preTransformAll = config.preTransform == PreTransform.ALL;
			TransformLogger privateLogger = logger.cloneIf(preTransformAll);
			RewriterModule anonymizer = new DexAnonymizer(new TypeAnonymizer(plan, reanonymize), privateLogger,
					logPrefix, DEBUG, config.treatReanonymizeErrorsAsWarnings ? WARN : ERROR).getModule();
			dex = transformDex(dex, anonymizer);
			if (preTransformAll) preTransformDex(dex, privateLogger, logPrefix);
		}
		return dex;
	}

	private DexFile encodeDex(DexFile dex, boolean enabled, DexMap dexMap, EncoderConfiguration encoderConfig,
			TransformLogger logger, String logPrefix) {
		if (enabled) {
			boolean preTransformAll = config.preTransform == PreTransform.ALL;
			TransformLogger privateLogger = logger.cloneIf(preTransformAll);
			BasicStringEncoder basicStringEncoder = new BasicStringEncoder(config.codeMarker);
			BasicDexEncoder basicDexEncoder = new BasicDexEncoder(basicStringEncoder, privateLogger, logPrefix, DEBUG);
			EncoderDexMap encoderDexMap = new EncoderDexMap(dex, config.codeMarker, dexMap, encoderConfig);
			DexMap loggingDexMap = new LoggingDexMap(encoderDexMap, "encoded to '%s'", privateLogger, logPrefix, DEBUG);
			RewriterModule mapper = new DexMapperModule(loggingDexMap, basicDexEncoder.getModule());
			mapper = PatchRewriterModule.of(mapper, config.annotationPackage);
			dex = transformDex(dex, mapper);
			if (preTransformAll) preTransformDex(dex, privateLogger, logPrefix);
		}
		return dex;
	}

	private DexFile decodeDex(DexFile dex, boolean enabled, TransformLogger logger, String logPrefix) {
		if (enabled) {
			boolean preTransformAll = config.preTransform == PreTransform.ALL;
			TransformLogger privateLogger = logger.cloneIf(preTransformAll);
			RewriterModule decoder = new DexDecoder(stringDecoder, privateLogger, logPrefix, DEBUG,
					config.treatDecodeErrorsAsWarnings ? WARN : ERROR).getModule();
			dex = transformDex(dex, decoder);
			if (preTransformAll) preTransformDex(dex, privateLogger, logPrefix);
		}
		return dex;
	}

	private void preTransformDex(DexFile dex, TransformLogger logger, String logPrefix) {
		if (logger.isInUse()) {
			long time = System.nanoTime();
			// TODO: Implement multi-threaded visitor.
			//logger.setSync(...);
			new DexVisitor().visitDexFile(dex);
			time = System.nanoTime() - time;
			logStats(logPrefix, dex.getClasses().size(), time);
			logger.stopLogging();
		}
	}

	private Context createContext() {
		return new Context.Builder(logger)
			.setAnnotationPackage(config.annotationPackage)
			.setConstructorAutoIgnoreDisabled(config.constructorAutoIgnoreDisabled)
			.setSourceCodeRoot(config.sourceCodeRoot)
			.build();
	}

	private DexFile patchDex(DexFile sourceDex, DexFile patchDex) {
		long time = System.nanoTime();
		Opcodes patchedOpcodes = opcodes;
		if (patchedOpcodes == null) {
			Opcodes sourceOpcodes = sourceDex.getOpcodes();
			patchedOpcodes = OpcodeUtils.getNewestOpcodes(sourceOpcodes, patchDex.getOpcodes(), true);
			if (sourceOpcodes != null && patchedOpcodes != null && sourceOpcodes != patchedOpcodes) {
				int sourceDexVersion = OpcodeUtils.getDexVersionFromOpcodes(sourceOpcodes);
				int patchedDexVersion = OpcodeUtils.getDexVersionFromOpcodes(patchedOpcodes);
				if (sourceDexVersion != patchedDexVersion) {
					logger.log(INFO, String.format(Locale.ROOT, "patch changes dex version from '%03d' to '%03d'",
							sourceDexVersion, patchedDexVersion));
				}
			}
		}
		DexFile patchedDex = DexPatcher.process(createContext(), sourceDex, patchDex, patchedOpcodes);
		time = System.nanoTime() - time;
		logStats("patch process", sourceDex.getClasses().size() + patchDex.getClasses().size(), time);
		return patchedDex;
	}

	private DexFile readDex(File file) throws IOException {
		String message = "read '" + file + "'";
		logger.log(INFO, message);
		long time = System.nanoTime();
		DexFile dex = MultiDexIO.readDexFile(config.multiDex, file, dexFileNamer, opcodes, getIOLogger(message));
		time = System.nanoTime() - time;
		if (logger.isLogging(DEBUG) && opcodes == null && dex.getOpcodes() != null) {
			int dexVersion = OpcodeUtils.getDexVersionFromOpcodes(dex.getOpcodes());
			logger.log(DEBUG, String.format(Locale.ROOT, message + ": dex version '%03d'", dexVersion));
		}
		logStats(message, dex.getClasses().size(), time);
		return dex;
	}

	private void writeDex(File file, DexFile dex) throws IOException {
		String message = "write '" + file + "'";
		logger.log(INFO, message);
		if (logger.isLogging(DEBUG) && dex.getOpcodes() != null) {
			int dexVersion = OpcodeUtils.getDexVersionFromOpcodes(dex.getOpcodes());
			logger.log(DEBUG, String.format(Locale.ROOT, message + ": dex version '%03d'", dexVersion));
		}
		long time = System.nanoTime();
		MultiDexIO.writeDexFile(config.multiDex, config.multiDexJobs, file, dexFileNamer,
				dex, config.maxDexPoolSize, getIOLogger(message));
		time = System.nanoTime() - time;
		logStats(message, dex.getClasses().size(), time);
	}

	private DexIO.Logger getIOLogger(final String header) {
		if (!logger.isLogging(DEBUG)) return null;
		return new DexIO.Logger() {
			@Override
			public void log(File file, String entryName, int typeCount) {
				if (logger.isLogging(DEBUG)) {
					String h = header;
					// See https://github.com/DexPatcher/multidexlib2/commit/177350fbba4d490111d1362810bbeb4521d1803b
					//noinspection StringEquality
					if (entryName != SingletonDexContainer.UNDEFINED_ENTRY_NAME) {
						h += ": file '" + entryName + "'";
					}
					logger.log(DEBUG, h + ": " + typeCount + " types");
				}
			}
		};
	}

	private void logStats(String header, int typeCount, long nanoTime) {
		if (config.timingStats) {
			logger.log(NONE, "stats: " + header + ": " +
					typeCount + " types, " +
					((nanoTime + 500000) / 1000000) + " ms" +
					(typeCount != 0 ? ", " + (((nanoTime / typeCount) + 500) / 1000) + " us/type" : ""));
		}
	}

}
