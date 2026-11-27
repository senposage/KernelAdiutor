/*
 * Copyright (C) 2026
 *
 * This file is part of Kernel Adiutor.
 */
package com.grarak.kerneladiutor.utils.kernel.gpu;

import android.content.Context;

import com.grarak.kerneladiutor.fragments.ApplyOnBootFragment;
import com.grarak.kerneladiutor.utils.Log;
import com.grarak.kerneladiutor.utils.Utils;
import com.grarak.kerneladiutor.utils.root.Control;
import com.grarak.kerneladiutor.utils.root.RootUtils;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
    private static final String PER_BIN_VOLTAGE = "/proc/gpufreq/gpufreq_oc_opp_voltage";
    private static final String DVFSRC = "/sys/devices/platform/10012000.dvfsrc/helio-dvfsrc";
    private static final String TOP_VCORE = DVFSRC + "/dvfsrc_top_vcore_uv";
    private static final String TOP_VCORE_MAX = DVFSRC + "/dvfsrc_top_vcore_max_uv";
    private static final String VSRAM_TEST = DVFSRC + "/dvfsrc_vsram_test_uv";
    private static final String RAIL_PM = DVFSRC + "/dvfsrc_gpu_rail_pm";
    public static final int VCORE_STOCK_UV = 800000;
    public static final int VSRAM_STOCK_UV = 900000;
    public static final int RAIL_GAP_UV = 100000;
    public static final String RAILS_SETTING = "t227u_gpu_rail_targets";
    private static final String TAG = "T227GPU";
    private static final int VCORE_STEP_UV = 6250;
    private static final Pattern VCORE_INPUT = Pattern.compile("[\\p{Nd}]+(?:\\.[\\p{Nd}]*)?");
    private static final Pattern VCORE_STATE = Pattern.compile(
            "programmed_uv=(\\d+|unavailable)\\s+live_uv=(-?\\d+)\\s+active_gear=(-?\\d+)");
    private static final Pattern VSRAM_STATE = Pattern.compile(
            "vsram_uv=(-?\\d+)\\s+vcore_uv=(-?\\d+)\\s+active_gear=-?\\d+\\s+floor_active=[01]"
                    + "(?:\\s+max_uv=(\\d+)\\s+min_delta_uv=\\d+)?");
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
        public final int maxUv;

        private VsramState(int vsramUv, int vcoreUv, int maxUv) {
            this.vsramUv = vsramUv;
            this.vcoreUv = vcoreUv;
            this.maxUv = maxUv;
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

    public static boolean restoresRailsAfterSuspend() {
        if (!Utils.existFile(RAIL_PM)) return false;
        String value = Utils.readFile(RAIL_PM);
        return value != null && "1".equals(value.trim());
    }

    public static VsramState readVsram() {
        return parseVsram(Utils.readFile(VSRAM_TEST));
    }

    private static VsramState parseVsram(String readback) {
        if (readback == null) return null;
        Matcher match = VSRAM_STATE.matcher(readback.trim());
        if (!match.matches()) return null;
        return new VsramState(Utils.strToInt(match.group(1)), Utils.strToInt(match.group(2)),
                Utils.strToInt(match.group(3)));
    }

    public static int getVcoreMaxUv(VsramState vsram) {
        if (vsram == null || vsram.vsramUv < VSRAM_STOCK_UV) return VCORE_STOCK_UV;
        String reportedLimit = Utils.readFile(TOP_VCORE_MAX);
        int limit = reportedLimit == null ? 0 : Utils.strToInt(reportedLimit.trim());
        return limit >= VCORE_STOCK_UV
                ? Math.min(limit, vsram.vsramUv - RAIL_GAP_UV)
                : vsram.vsramUv - RAIL_GAP_UV;
    }

    public static int voltageStepAtOrBelow(String millivolts) {
        BigDecimal microvolts = parseMicrovolts(millivolts);
        if (microvolts == null) return 0;
        return microvolts.intValue() / VCORE_STEP_UV * VCORE_STEP_UV;
    }

    public static int voltageFromOffset(String millivolts) {
        String input = millivolts.trim();
        if (input.startsWith("+")) input = input.substring(1);
        BigDecimal offset = parseMicrovolts(input);
        if (offset == null || offset.compareTo(
                BigDecimal.valueOf(Integer.MAX_VALUE - VCORE_STOCK_UV)) > 0) return 0;
        int steps = offset.divide(BigDecimal.valueOf(VCORE_STEP_UV), 0, RoundingMode.HALF_UP)
                .intValue();
        return VCORE_STOCK_UV + steps * VCORE_STEP_UV;
    }

    public static int getOffsetVcoreMaxUv(VsramState vsram) {
        return vsram != null && vsram.maxUv >= VSRAM_STOCK_UV
                ? vsram.maxUv - RAIL_GAP_UV : getVcoreMaxUv(vsram);
    }

    private static BigDecimal parseMicrovolts(String millivolts) {
        char separator = DecimalFormatSymbols.getInstance().getDecimalSeparator();
        String input = millivolts.trim().replace(separator, '.').replace(',', '.');
        if (!VCORE_INPUT.matcher(input).matches()) return null;

        BigDecimal microvolts = new BigDecimal(input).movePointRight(3);
        return microvolts.compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) <= 0 ? microvolts : null;
    }

    private static boolean isVcoreStep(int microvolts) {
        return microvolts > 0 && microvolts % VCORE_STEP_UV == 0;
    }

    public static VcoreState readVcore() {
        return parseVcore(Utils.readFile(TOP_VCORE));
    }

    private static VcoreState parseVcore(String readback) {
        if (readback == null) return null;
        Matcher match = VCORE_STATE.matcher(readback.trim());
        if (!match.matches()) return null;
        return new VcoreState(Utils.strToInt(match.group(1)),
                Utils.strToInt(match.group(2)), Utils.strToInt(match.group(3)));
    }

    public static synchronized boolean setRail(boolean vsram, boolean offset,
                                              int targetUv, Context context) {
        RootUtils.SU su = RootUtils.getSU();
        VsramState currentSram = readCurrentVsram(su);
        VcoreState currentCore = parseVcore(su.runCommand("cat " + TOP_VCORE));
        if (currentSram == null || currentCore == null || !currentCore.canAdjust()) return false;

        int sramUv = vsram ? targetUv : currentSram.vsramUv;
        int coreUv = vsram ? Math.min(currentCore.programmedUv, sramUv - RAIL_GAP_UV) : targetUv;
        if (offset) sramUv = coreUv + RAIL_GAP_UV;
        if (!applyRails(su, currentSram, sramUv, coreUv)) return false;

        Control.saveSetting(sramUv + " " + coreUv, ApplyOnBootFragment.GPU, RAILS_SETTING, context);
        return true;
    }

    public static synchronized boolean restoreRails(RootUtils.SU su, String targets) {
        String[] values = targets.trim().split("\\s+");
        if (values.length != 2) return false;
        int sramUv = Utils.strToInt(values[0]);
        int coreUv = Utils.strToInt(values[1]);
        VsramState currentSram = readCurrentVsram(su);
        return currentSram != null && applyRails(su, currentSram, sramUv, coreUv);
    }

    private static boolean applyRails(RootUtils.SU su, VsramState currentSram, int sramUv, int coreUv) {
        if (!isVcoreStep(sramUv) || sramUv < VSRAM_STOCK_UV
                || (currentSram.maxUv > 0 && sramUv > currentSram.maxUv)
                || !isVcoreStep(coreUv) || coreUv < VCORE_STOCK_UV
                || coreUv > sramUv - RAIL_GAP_UV) return false;

        if (sramUv < currentSram.vsramUv && !writeRail(su, false, coreUv)) return false;
        if (sramUv != currentSram.vsramUv && !writeRail(su, true, sramUv)) return false;
        // Reapply even an unchanged slot to let the kernel refresh an active gear.
        return sramUv < currentSram.vsramUv || writeRail(su, false, coreUv);
    }

    private static VsramState readCurrentVsram(RootUtils.SU su) {
        for (int attempt = 0; attempt < 4; attempt++) {
            if (attempt > 0 && !waitForRail()) break;
            VsramState state = parseVsram(su.runCommand("cat " + VSRAM_TEST));
            if (state != null && state.vsramUv >= VSRAM_STOCK_UV) return state;
        }
        return null;
    }

    private static boolean writeRail(RootUtils.SU su, boolean vsram, int targetUv) {
        String path = vsram ? VSRAM_TEST : TOP_VCORE;
        int stock = vsram ? VSRAM_STOCK_UV : VCORE_STOCK_UV;
        String write = Control.write(String.valueOf(targetUv == stock ? 0 : targetUv), path);
        boolean accepted = false;
        for (int attempt = 0; attempt < 4; attempt++) {
            if (attempt > 0 && !waitForRail()) break;
            if (!accepted) accepted = "applied".equals(su.runCommand(write + " && echo applied"));
            if (!accepted) continue;

            String readback = su.runCommand("cat " + path);
            if (vsram) {
                VsramState state = parseVsram(readback);
                if (state != null && state.vsramUv == targetUv) return true;
            } else {
                VcoreState state = parseVcore(readback);
                if (state != null && state.programmedUv == targetUv) return true;
            }
        }
        Log.e(TAG, (accepted ? "Unverified rail write " : "Rejected rail write ") + path + " " + targetUv);
        return false;
    }

    private static boolean waitForRail() {
        try {
            Thread.sleep(100);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
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

    public static boolean isMaximumFrequencySetting(String id) {
        return MAX_FREQ.equals(id);
    }

    public static boolean isLegacyPerBinVoltageSetting(String id) {
        return id != null && (PER_BIN_VOLTAGE.equals(id) || id.startsWith(PER_BIN_VOLTAGE + ":"));
    }
}
