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
    private static final String LZ4 = "lz4";

    public static void setDisksize(final int value, final Context context) {
        String maxCompStrems = null;
        if (Utils.existFile(MAX_COMP_STREAMS)) {
            maxCompStrems = Utils.readFile(MAX_COMP_STREAMS);
        }
        int size = value * 1024 * 1024;
        run("swapoff " + BLOCK + " > /dev/null 2>&1", BLOCK + "swapoff", context);
        run(Control.write("1", RESET), RESET, context);
        // A reset restores the driver's default compressor.  Prefer LZ4 when
        // the active kernel advertises it: it is the tested low-latency choice
        // for the T227U, while leaving kernels without LZ4 untouched.
        if (hasLz4()) {
            run(Control.write(LZ4, COMP_ALGORITHM), COMP_ALGORITHM, context);
        }
        if (maxCompStrems != null) {
            run(Control.write(maxCompStrems, MAX_COMP_STREAMS), MAX_COMP_STREAMS, context);
        }
        if (size != 0) {
            run(Control.write(String.valueOf(size), DISKSIZE), DISKSIZE, context);
            run("mkswap " + BLOCK + " > /dev/null 2>&1", BLOCK + "mkswap", context);
            run("swapon " + BLOCK + " > /dev/null 2>&1", BLOCK + "swapon", context);
        }
    }

    public static int getDisksize() {
        return Utils.strToInt(Utils.readFile(DISKSIZE)) / 1024 / 1024;
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

    private static boolean hasLz4() {
        String algorithms = getCompressionAlgorithms();
        return algorithms != null && algorithms.contains(LZ4);
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
