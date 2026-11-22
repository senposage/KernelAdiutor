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
import com.grarak.kerneladiutor.utils.root.RootUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** MT6765/T227 GPU DVFS and shared VCORE controls. */
public final class T227GPU {

    private static final String OPP_DUMP = "/proc/gpufreq/gpufreq_opp_dump";
    private static final String VAR_DUMP = "/proc/gpufreq/gpufreq_var_dump";
    private static final String MAX_FREQ = "/proc/gpufreq/gpufreq_oc_max_freq";
    private static final String TOP_VCORE =
            "/sys/devices/platform/10012000.dvfsrc/helio-dvfsrc/dvfsrc_top_vcore_uv";
    private static final List<Integer> VCORE_VOLTAGES = Collections.unmodifiableList(
            Arrays.asList(800000, 806250, 812500, 818750, 825000));
    private static final Pattern VCORE_STATE = Pattern.compile(
            "programmed_uv=(\\d+|unavailable)\\s+live_uv=(-?\\d+)\\s+active_gear=(-?\\d+)");
    private static final Pattern FREQUENCY = Pattern.compile("freq\\s*=\\s*(\\d+)");
    private static final Pattern CURRENT = Pattern.compile("g_cur_opp_freq\\s*=\\s*(\\d+)");
    private static final Pattern MAXIMUM = Pattern.compile("max_freq_khz\\s*=\\s*(\\d+)");

    public static final class VcoreState {
        public final int programmedUv;
        public final int liveUv;
        public final int activeGear;

        private VcoreState(int programmedUv, int liveUv, int activeGear) {
            this.programmedUv = programmedUv;
            this.liveUv = liveUv;
            this.activeGear = activeGear;
        }

        public boolean canAdjust() {
            return VCORE_VOLTAGES.contains(programmedUv);
        }
    }

    private T227GPU() {
    }

    public static boolean supported() {
        return Utils.existFile(OPP_DUMP) && Utils.existFile(VAR_DUMP)
                && Utils.existFile(MAX_FREQ);
    }

    public static boolean hasVcoreControl() {
        return Utils.existFile(TOP_VCORE);
    }

    public static List<Integer> getVcoreVoltages() {
        return VCORE_VOLTAGES;
    }

    public static VcoreState readVcore() {
        return parseVcore(Utils.readFile(TOP_VCORE));
    }

    public static VcoreState setVcore(int microvolts) {
        if (!VCORE_VOLTAGES.contains(microvolts)) return null;

        int request = microvolts == VCORE_VOLTAGES.get(0) ? 0 : microvolts;
        VcoreState result = parseVcore(RootUtils.runCommand(
                Control.write(String.valueOf(request), TOP_VCORE) + " && cat " + TOP_VCORE));
        return result != null && result.programmedUv == microvolts ? result : null;
    }

    private static VcoreState parseVcore(String readback) {
        if (readback == null) return null;
        Matcher match = VCORE_STATE.matcher(readback.trim());
        if (!match.matches()) return null;
        return new VcoreState(Utils.strToInt(match.group(1)),
                Utils.strToInt(match.group(2)), Utils.strToInt(match.group(3)));
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
