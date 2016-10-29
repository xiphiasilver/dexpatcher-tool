/*
 * DexPatcher - Copyright 2015, 2016 Rodrigo Balerdi
 * (GNU General Public License version 3 or later)
 *
 * DexPatcher is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 */

package lanchon.dexpatcher.multidex;

public class DexVersionMap {

	// This should be part of dexlib2.

	public static boolean isSupportedApiLevel(int apiLevel) {
		return  apiLevel <= 24;
	}

	public static int getHighestDexVersionFromApiLevel(int apiLevel) {
		return  apiLevel <= 23 ? 35 :
				37;
	}

	public static boolean isSupportedDexVersion(int dexVersion) {
		return  dexVersion == 35 ||
				dexVersion == 37;
	}

	public static int getHighestApiLevelFromDexVersion(int dexVersion) {
		return  dexVersion <= 35 ? 23 :
				dexVersion <= 37 ? 24 :
				10000;      // Build.VERSION_CODES.CUR_DEVELOPMENT
	}

	private DexVersionMap() {}

}
