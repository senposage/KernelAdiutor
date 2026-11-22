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

import java.math.BigDecimal;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** MT6765/T227 GPU DVFS and shared VCORE controls. */
public final class T227GPU {

    private static final String OPP_DUMP = "/proc/gpufreq/gpufreq_opp_dump";
    private static final String VAR_DUMP = "/proc/gpufreq/gpufreq_var_dump";
    private static final String MAX_FREQ = "/proc/gpufreq/gpufreq_oc_max_freq";
    private static final String DVFSRC = "/sys/devices/platform/10012000.dvfsrc/helio-dvfsrc";
    private static final String TOP_VCORE = DVFSRC + "/dvfsrc_top_vcore_uv";
    private static final String TOP_VCORE_MAX = DVFSRC + "/dvfsrc_top_vcore_max_uv";
    private static final String VSRAM_TEST = DVFSRC + "/dvfsrc_vsram_test_uv";
    public static final int VCORE_STOCK_UV = 800000;
    public static final int VSRAM_STOCK_UV = 900000;
    private static final int VCORE_STEP_UV = 6250;
    private static final Pattern VCORE_INPUT = Pattern.compile("[\\p{Nd}]+(?:\\.[\\p{Nd}]*)?");
    private static final Pattern VCORE_STATE = Pattern.compile(
            "programmed_uv=(\\d+|unavailable)\\s+live_uv=(-?\\d+)\\s+active_gear=(-?\\d+)");
    private static final Pattern VSRAM_STATE = Pattern.compile(
            "vsram_uv=(-?\\d+)\\s+vcore_uv=(-?\\d+)\\s+active_gear=-?\\d+\\s+floor_active=([01])"
                    + "(?:\\s+max_uv=(\\d+)\\s+min_delta_uv=(\\d+))?");
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
            return isVcoreStep(programmedUv);
        }
    }

    public static final class VsramState {
        public final int vsramUv;
        public final int vcoreUv;
        public final boolean floorActive;
        public final int maxUv;
        public final int minimumGapUv;

        private VsramState(int vsramUv, int vcoreUv, boolean floorActive, int maxUv,
                           int minimumGapUv) {
            this.vsramUv = vsramUv;
            this.vcoreUv = vcoreUv;
            this.floorActive = floorActive;
            this.maxUv = maxUv;
            this.minimumGapUv = minimumGapUv;
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

    public static boolean hasVsramControl() {
        return Utils.existFile(VSRAM_TEST);
    }

    public static VsramState readVsram() {
        return parseVsram(Utils.readFile(VSRAM_TEST));
    }

    public static VsramState setVsram(int microvolts) {
        if (!isVcoreStep(microvolts)) return null;

        boolean reset = microvolts == VSRAM_STOCK_UV;
        int request = reset ? 0 : microvolts;
        VsramState result = parseVsram(RootUtils.runCommand(
                Control.write(String.valueOf(request), VSRAM_TEST) + " && cat " + VSRAM_TEST));
        return result != null && result.vsramUv == microvolts
                && result.floorActive != reset ? result : null;
    }

    private static VsramState parseVsram(String readback) {
        if (readback == null) return null;
        Matcher match = VSRAM_STATE.matcher(readback.trim());
        if (!match.matches()) return null;
        return new VsramState(Utils.strToInt(match.group(1)), Utils.strToInt(match.group(2)),
                "1".equals(match.group(3)), Utils.strToInt(match.group(4)),
                Utils.strToInt(match.group(5)));
    }

    public static int getVcoreMaxUv() {
        String limit = Utils.readFile(TOP_VCORE_MAX);
        return limit == null ? 0 : Utils.strToInt(limit.trim());
    }

    public static int voltageStepAtOrBelow(String millivolts) {
        char separator = DecimalFormatSymbols.getInstance().getDecimalSeparator();
        String input = millivolts.trim().replace(separator, '.').replace(',', '.');
        if (!VCORE_INPUT.matcher(input).matches()) return 0;

        BigDecimal microvolts = new BigDecimal(input).movePointRight(3);
        if (microvolts.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) > 0) return 0;
        return microvolts.intValue() / VCORE_STEP_UV * VCORE_STEP_UV;
    }

    private static boolean isVcoreStep(int microvolts) {
        return microvolts > 0 && microvolts % VCORE_STEP_UV == 0;
    }

    public static VcoreState readVcore() {
        return parseVcore(Utils.readFile(TOP_VCORE));
    }

    public static VcoreState setVcore(int microvolts) {
        if (!isVcoreStep(microvolts)) return null;

        int request = microvolts == VCORE_STOCK_UV ? 0 : microvolts;
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
