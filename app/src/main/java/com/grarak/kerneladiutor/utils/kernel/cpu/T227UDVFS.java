/*
 * Copyright (C) 2026
 *
 * This file is part of Kernel Adiutor.
 */
package com.grarak.kerneladiutor.utils.kernel.cpu;

import android.content.Context;

import com.grarak.kerneladiutor.utils.Utils;
import com.grarak.kerneladiutor.utils.root.Control;

/**
 * MT6765 (T227U) DVFS policy helpers.
 *
 * PPM synchronously constrains generic scaling_{min,max}_freq writes on this
 * platform.  The MTK proc endpoints below are the driver-owned, persistent
 * user envelope: they are the only controls which survive the PPM update
 * path.  CPU0-3 are the L policy and CPU4-7 are the LL policy.
 */
public final class T227UDVFS {

    private static final String L_MAX =
            "/proc/cpufreq/MT_CPU_DVFS_L/cpufreq_oc_max_freq";
    private static final String LL_MAX =
            "/proc/cpufreq/MT_CPU_DVFS_LL/cpufreq_oc_max_freq";
    private static final String LL_MIN =
            "/proc/cpufreq/MT_CPU_DVFS_LL/cpufreq_oc_min_freq";
    private static final String CPU_SCALING_GOVERNOR =
            "/sys/devices/system/cpu/cpu%d/cpufreq/scaling_governor";
    private static final String CPU_AVAILABLE_GOVERNORS =
            "/sys/devices/system/cpu/cpu%d/cpufreq/scaling_available_governors";
    private static final String LEGACY_CPU_MAX_FREQ =
            "/sys/devices/system/cpu/cpu%d/cpufreq/scaling_max_freq";
    private static final String LEGACY_CPU_MIN_FREQ =
            "/sys/devices/system/cpu/cpu%d/cpufreq/scaling_min_freq";
    private static final String CPU_GOVERNOR_TUNABLES_CORE =
            "/sys/devices/system/cpu/cpu%d/cpufreq/%s";
    private static final String CPU_GOVERNOR_TUNABLES =
            "/sys/devices/system/cpu/cpufreq/%s";

    private T227UDVFS() {
    }

    public static boolean supported() {
        return Utils.existFile(L_MAX) && Utils.existFile(LL_MAX);
    }

    public static boolean isLPolicy(int cpu) {
        return cpu < 4;
    }

    public static int getPolicyCpu(int cpu) {
        return isLPolicy(cpu) ? 0 : 4;
    }

    public static String getMaxPath(int cpu) {
        return isLPolicy(cpu) ? L_MAX : LL_MAX;
    }

    public static int getMaxFreq(int cpu) {
        return Utils.strToInt(Utils.readFile(getMaxPath(cpu)));
    }

    public static void setMaxFreq(int cpu, int frequency, String category, Context context) {
        removeLegacyFrequencySettings(context);
        String path = getMaxPath(cpu);
        Control.runSetting(Control.write(String.valueOf(frequency), path), category, path, context);
    }

    /** The MT6765 vendor driver exposes a persistent user floor only for LL. */
    public static boolean hasMinControl(int cpu) {
        return !isLPolicy(cpu) && Utils.existFile(LL_MIN);
    }

    public static int getMinFreq(int cpu) {
        return hasMinControl(cpu) ? Utils.strToInt(Utils.readFile(LL_MIN)) : 0;
    }

    public static void setMinFreq(int cpu, int frequency, String category, Context context) {
        removeLegacyFrequencySettings(context);
        if (hasMinControl(cpu)) {
            Control.runSetting(Control.write(String.valueOf(frequency), LL_MIN), category, LL_MIN,
                    context);
        }
    }

    public static String getGovernorPath(int cpu) {
        return Utils.strFormat(CPU_SCALING_GOVERNOR, getPolicyCpu(cpu));
    }

    public static String getGovernor(int cpu) {
        String governor = Utils.readFile(getGovernorPath(cpu));
        return governor == null ? "" : governor.trim();
    }

    public static void setGovernor(int cpu, String governor, String category, Context context) {
        String path = getGovernorPath(cpu);
        Control.runSetting(Control.write(governor, path), category, path, context);
    }

    public static String getGovernorTunablesPath(int cpu, String governor) {
        String policyPath = Utils.strFormat(CPU_GOVERNOR_TUNABLES_CORE,
                getPolicyCpu(cpu), governor);
        if (Utils.existFile(policyPath)) return policyPath;
        return Utils.strFormat(CPU_GOVERNOR_TUNABLES, governor);
    }

    public static String getAvailableGovernors(int cpu) {
        return Utils.readFile(Utils.strFormat(CPU_AVAILABLE_GOVERNORS, getPolicyCpu(cpu)));
    }

    /**
     * KA versions before the MT6765 adapter persisted generic cpufreq limit
     * requests. PPM rejects them and they become duplicate, misleading
     * Apply-on-Boot records. Keep unrelated governor and hotplug choices.
     */
    public static void removeLegacyFrequencySettings(Context context) {
        for (int policyCpu : new int[]{0, 4}) {
            Control.removeSetting("cpu_onboot",
                    Utils.strFormat(LEGACY_CPU_MAX_FREQ, policyCpu) + policyCpu, context);
            Control.removeSetting("cpu_onboot",
                    Utils.strFormat(LEGACY_CPU_MIN_FREQ, policyCpu) + policyCpu, context);
        }
    }
}
