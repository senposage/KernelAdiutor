/*
 * Copyright (C) 2015-2016 Willi Ye <williye97@gmail.com>
 *
 * This file is part of Kernel Adiutor.
 *
 * Kernel Adiutor is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Kernel Adiutor is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Kernel Adiutor.  If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.grarak.kerneladiutor.utils.kernel.vm;

import android.content.Context;

import com.grarak.kerneladiutor.fragments.ApplyOnBootFragment;
import com.grarak.kerneladiutor.utils.Utils;
import com.grarak.kerneladiutor.utils.root.Control;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Created by willi on 03.08.16.
 */
public class ZRAM {

    private static final String ZRAM = "/sys/block/zram0";
    private static final String BLOCK = "/dev/block/zram0";
    private static final String DISKSIZE = "/sys/block/zram0/disksize";
    private static final String RESET = "/sys/block/zram0/reset";
    private static final String MAX_COMP_STREAMS = "/sys/block/zram0/max_comp_streams";
    private static final String COMP_ALGORITHM = "/sys/block/zram0/comp_algorithm";
    private static final String COMP_DICTIONARY_SIZE = ZRAM + "/comp_dictionary_size";
    public static final String DICTIONARY_FILE = "/sdcard/Download/KernelAdiutor/zram-lz4.dict";
    private static final Pattern SAVED_SIZE = Pattern.compile(
            "^(?:\\(ka_zram_bytes=([0-9]+);|echo '([0-9]+)' > /sys/block/zram0/disksize$)");

    public static void setDisksize(final int value, final Context context) {
        String command = setupCommand(value * 1024L * 1024L, false, context);
        if (command != null) run(command, DISKSIZE, context);
    }

    public static boolean isSizeSetting(String id) {
        return DISKSIZE.equals(id);
    }

    public static boolean isSetupSetting(String id) {
        return isSizeSetting(id) || RESET.equals(id) || COMP_ALGORITHM.equals(id)
                || MAX_COMP_STREAMS.equals(id) || (BLOCK + "swapoff").equals(id)
                || (BLOCK + "mkswap").equals(id) || (BLOCK + "swapon").equals(id)
                || (BLOCK + "swapoff-algorithm").equals(id)
                || (RESET + "algorithm").equals(id) || (DISKSIZE + "reset").equals(id);
    }

    /** Upgrade old saved resize commands without requiring another manual apply. */
    public static String bootCommand(String saved, Context context) {
        if (saved == null) return null;
        Matcher match = SAVED_SIZE.matcher(saved);
        if (!match.find()) return null;
        try {
            return setupCommand(Long.parseLong(match.group(1) == null
                    ? match.group(2) : match.group(1)), true, context);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static String setupCommand(long bytes, boolean boot, Context context) {
        if (context == null || bytes < 0 || bytes > 2048L * 1024L * 1024L) return null;
        String script = Utils.readAssetFile(context, "t227u_zram_setup.sh");
        if (script == null || script.isEmpty()) return null;
        return "(ka_zram_bytes=" + bytes + ";\n"
                + "ka_zram_boot=" + (boot ? 1 : 0) + "\n"
                + "ka_zram_dir='" + ZRAM + "'\n"
                + "ka_zram_block='" + BLOCK + "'\n"
                + "ka_zram_proc_swaps='/proc/swaps'\n"
                + "ka_zram_dictionary='" + DICTIONARY_FILE + "'\n"
                + "ka_zram_dictionary_fallback='/data/media/0/Download/KernelAdiutor/zram-lz4.dict'\n"
                + script + "\n)";
    }

    public static int getDisksize() {
        return (int) (Utils.strToLong(Utils.readFile(DISKSIZE)) / 1024 / 1024);
    }

    public static String getCompressionAlgorithm() {
        String algorithms = getCompressionAlgorithms();
        if (algorithms == null) return null;
        int start = algorithms.indexOf('[');
        int end = algorithms.indexOf(']', start + 1);
        return start >= 0 && end > start ? algorithms.substring(start + 1, end) : algorithms;
    }

    public static String getMaxCompressionStreams() {
        return Utils.readFile(MAX_COMP_STREAMS);
    }

    public static boolean supportsDictionary() {
        return Utils.existFile(COMP_DICTIONARY_SIZE);
    }

    public static int getDictionarySize() {
        return Utils.strToInt(Utils.readFile(COMP_DICTIONARY_SIZE));
    }

    private static String getCompressionAlgorithms() {
        return Utils.readFile(COMP_ALGORITHM);
    }

    public static boolean supported() {
        return Utils.existFile(ZRAM);
    }

    private static void run(String command, String id, Context context) {
        Control.runSetting(command, ApplyOnBootFragment.VM, id, context);
    }

}
