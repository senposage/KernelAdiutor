/*
 * Copyright (C) 2026
 *
 * This file is part of Kernel Adiutor.
 */
package com.grarak.kerneladiutor.utils.kernel.gpu;

import android.content.Context;

import com.grarak.kerneladiutor.fragments.ApplyOnBootFragment;
import com.grarak.kerneladiutor.utils.Utils;
import com.grarak.kerneladiutor.utils.root.Control;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** MT6765/T227 GPU DVFS ceiling ABI. */
public final class T227GPU {

    private static final String OPP_DUMP = "/proc/gpufreq/gpufreq_opp_dump";
    private static final String VAR_DUMP = "/proc/gpufreq/gpufreq_var_dump";
    private static final String MAX_FREQ = "/proc/gpufreq/gpufreq_oc_max_freq";
    private static final Pattern FREQUENCY = Pattern.compile("freq\\s*=\\s*(\\d+)");
    private static final Pattern CURRENT = Pattern.compile("g_cur_opp_freq\\s*=\\s*(\\d+)");
    private static final Pattern MAXIMUM = Pattern.compile("max_freq_khz\\s*=\\s*(\\d+)");

    private T227GPU() {
    }

    public static boolean supported() {
        return Utils.existFile(OPP_DUMP) && Utils.existFile(VAR_DUMP)
                && Utils.existFile(MAX_FREQ);
    }

    public static List<Integer> getFrequencies() {
        List<Integer> frequencies = new ArrayList<>();
        String dump = Utils.readFile(OPP_DUMP);
        if (dump == null) return frequencies;
        Matcher matcher = FREQUENCY.matcher(dump);
        while (matcher.find()) {
            int frequency = Utils.strToInt(matcher.group(1));
            if (frequency > 0 && !frequencies.contains(frequency)) frequencies.add(frequency);
        }
        Collections.sort(frequencies);
        return frequencies;
    }

    public static int getCurrentFrequency() {
        String dump = Utils.readFile(VAR_DUMP);
        if (dump == null) return 0;
        Matcher matcher = CURRENT.matcher(dump);
        return matcher.find() ? Utils.strToInt(matcher.group(1)) : 0;
    }

    public static int getMaximumFrequency() {
        String value = Utils.readFile(MAX_FREQ);
        if (value == null) return 0;
        Matcher matcher = MAXIMUM.matcher(value);
        return matcher.find() ? Utils.strToInt(matcher.group(1)) : 0;
    }

    public static void setMaximumFrequency(int frequency, Context context) {
        Control.runSetting(Control.write(String.valueOf(frequency), MAX_FREQ),
                ApplyOnBootFragment.GPU, MAX_FREQ, context);
    }
}
