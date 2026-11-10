/*
 * Copyright (C) 2026
 *
 * This file is part of Kernel Adiutor.
 */
package com.grarak.kerneladiutor.utils.kernel.cpu;

import com.grarak.kerneladiutor.utils.Utils;
import com.grarak.kerneladiutor.utils.root.Control;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * MT8173/Suez policy helpers.
 *
 * The MTK hotplug service can remove the normal cpufreq policy for an idle
 * cluster.  These proc controls live with the MTK DVFS driver instead, so
 * maximum-frequency selections survive a cluster offline/online transition.
 */
public final class SuezDVFS {

    private static final String LITTLE_MAX =
            "/proc/cpufreq/MT_CPU_DVFS_LITTLE/cpufreq_max_freq";
    private static final String BIG_MAX =
            "/proc/cpufreq/MT_CPU_DVFS_BIG/cpufreq_max_freq";
    private static final String LITTLE_CURRENT =
            "/proc/cpufreq/MT_CPU_DVFS_LITTLE/cpufreq_freq";
    private static final String BIG_CURRENT =
            "/proc/cpufreq/MT_CPU_DVFS_BIG/cpufreq_freq";
    private static final String LITTLE_TIME_IN_STATE =
            "/proc/cpufreq/MT_CPU_DVFS_LITTLE/cpufreq_time_in_state";
    private static final String BIG_TIME_IN_STATE =
            "/proc/cpufreq/MT_CPU_DVFS_BIG/cpufreq_time_in_state";
    private static final String LITTLE_OPP_VOLTAGE =
            "/proc/cpufreq/MT_CPU_DVFS_LITTLE/cpufreq_opp_voltage";
    private static final String BIG_OPP_VOLTAGE =
            "/proc/cpufreq/MT_CPU_DVFS_BIG/cpufreq_opp_voltage";
    private static final int MINIMUM_VOLTAGE_MILLIVOLTS = 800;
    private static final String CPU_MIN_FREQ =
            "/sys/devices/system/cpu/cpu%d/cpufreq/scaling_min_freq";
    private static final String CPU_SCALING_GOVERNOR =
            "/sys/devices/system/cpu/cpu%d/cpufreq/scaling_governor";
    private static final String CPU_GOVERNOR_TUNABLES_CORE =
            "/sys/devices/system/cpu/cpu%d/cpufreq/%s";
    private static final String CPU_GOVERNOR_TUNABLES =
            "/sys/devices/system/cpu/cpufreq/%s";

    public static final class OppVoltage {
        private final int mFrequency;
        private final int mAssignedMillivolts;
        private final int mStockMillivolts;
        private final int mMaximumMillivolts;
        private final int mLiveVprocMillivolts;
        private final int mLiveVsramMillivolts;

        private OppVoltage(int frequency, int assignedMillivolts, int stockMillivolts,
                           int maximumMillivolts, int liveVprocMillivolts,
                           int liveVsramMillivolts) {
            mFrequency = frequency;
            mAssignedMillivolts = assignedMillivolts;
            mStockMillivolts = stockMillivolts;
            mMaximumMillivolts = maximumMillivolts;
            mLiveVprocMillivolts = liveVprocMillivolts;
            mLiveVsramMillivolts = liveVsramMillivolts;
        }

        public int getFrequency() {
            return mFrequency;
        }

        public int getAssignedMillivolts() {
            return mAssignedMillivolts;
        }

        public int getStockMillivolts() {
            return mStockMillivolts;
        }

        public int getMaximumMillivolts() {
            return mMaximumMillivolts;
        }

        public int getLiveVprocMillivolts() {
            return mLiveVprocMillivolts;
        }

        public int getLiveVsramMillivolts() {
            return mLiveVsramMillivolts;
        }
    }

    private SuezDVFS() {
    }

    public static boolean supported() {
        return Utils.existFile(LITTLE_MAX) && Utils.existFile(BIG_MAX);
    }

    public static boolean isBigCpu(int cpu) {
        return cpu >= 2;
    }

    public static String getMaxPath(int cpu) {
        return isBigCpu(cpu) ? BIG_MAX : LITTLE_MAX;
    }

    public static String getOppVoltagePath(int cpu) {
        return isBigCpu(cpu) ? BIG_OPP_VOLTAGE : LITTLE_OPP_VOLTAGE;
    }

    public static boolean hasOppVoltageControl() {
        return Utils.existFile(LITTLE_OPP_VOLTAGE) && Utils.existFile(BIG_OPP_VOLTAGE);
    }

    public static int getMaxFreq(int cpu) {
        return Utils.strToInt(Utils.readFile(getMaxPath(cpu)));
    }

    /** Stable per-cluster physical clock readback; HPS may remove cpu2 sysfs. */
    public static int getCurrentFreq(int cpu) {
        return Utils.strToInt(Utils.readFile(isBigCpu(cpu) ? BIG_CURRENT : LITTLE_CURRENT));
    }

    /** Cluster-owned residency counter; unlike sysfs cpufreq stats it remains
     * available while HPS has parked the BIG cluster. */
    public static String getTimeInStatePath(int cpu) {
        return isBigCpu(cpu) ? BIG_TIME_IN_STATE : LITTLE_TIME_IN_STATE;
    }

    public static boolean hasTimeInState(int cpu) {
        return Utils.existFile(getTimeInStatePath(cpu));
    }

    public static void setMaxFreq(int cpu, int frequency, String category, String id,
                                  android.content.Context context) {
        Control.runSetting(Control.write(String.valueOf(frequency), getMaxPath(cpu)),
                category, id, context);
    }

    /**
     * MT8173 exposes a policy through each cluster's first CPU.  Do not fan a
     * write out to every CPU: the hotplug service may remove a sibling policy
     * and the generic per-core helper would then persist an unsafe CPU-online
     * action for boot.
     */
    public static int getPolicyCpu(int cpu) {
        int first = isBigCpu(cpu) ? 2 : 0;
        int last = isBigCpu(cpu) ? 3 : 1;

        /* HPS may make either core the policy representative.  Do not assume
         * cpu2 remains present after a big-cluster hotplug transition. */
        for (int candidate = first; candidate <= last; candidate++) {
            if (Utils.existFile(Utils.strFormat(CPU_SCALING_GOVERNOR, candidate))) {
                return candidate;
            }
        }
        return first;
    }

    /**
     * Return the cluster member which currently owns cpufreq statistics.
     *
     * The MTK HPS service is free to move the BIG policy between cpu2 and
     * cpu3.  Unlike the persistent DVFS proc controls, time_in_state lives
     * below the current policy's sysfs directory, so callers must not pin the
     * statistics reader to cpu2.
     */
    public static int getStatsCpu(int cpu) {
        int first = isBigCpu(cpu) ? 2 : 0;
        int last = isBigCpu(cpu) ? 3 : 1;

        for (int candidate = first; candidate <= last; candidate++) {
            if (Utils.existFile(Utils.strFormat(CPUFreq.TIME_STATE, candidate))
                    || Utils.existFile(Utils.strFormat(CPUFreq.TIME_STATE_2, candidate))) {
                return candidate;
            }
        }
        return getPolicyCpu(cpu);
    }

    public static String getMinPath(int cpu) {
        return Utils.strFormat(CPU_MIN_FREQ, getPolicyCpu(cpu));
    }

    public static int getMinFreq(int cpu) {
        return Utils.strToInt(Utils.readFile(getMinPath(cpu)));
    }

    public static void setMinFreq(int cpu, int frequency, String category,
                                  android.content.Context context) {
        String path = getMinPath(cpu);
        Control.runSetting(Control.write(String.valueOf(frequency), path), category, path, context);
    }

    public static String getGovernorPath(int cpu) {
        return Utils.strFormat(CPU_SCALING_GOVERNOR, getPolicyCpu(cpu));
    }

    public static String getGovernor(int cpu) {
        String governor = Utils.readFile(getGovernorPath(cpu));
        return governor == null ? "" : governor.trim();
    }

    public static void setGovernor(int cpu, String governor, String category,
                                   android.content.Context context) {
        String path = getGovernorPath(cpu);
        Control.runSetting(Control.write(governor, path), category, path, context);
    }

    public static String getGovernorTunablesPath(int cpu, String governor) {
        String policyPath = Utils.strFormat(CPU_GOVERNOR_TUNABLES_CORE,
                getPolicyCpu(cpu), governor);
        if (Utils.existFile(policyPath)) return policyPath;
        return Utils.strFormat(CPU_GOVERNOR_TUNABLES, governor);
    }

    public static List<OppVoltage> getOppVoltages(int cpu) {
        List<OppVoltage> voltages = new ArrayList<>();
        String[] lines = Utils.readFile(getOppVoltagePath(cpu)).split("\\n");
        int maximum = 0;
        int liveVproc = 0;
        int liveVsram = 0;

        for (String line : lines) {
            line = line.trim();
            if (line.startsWith("#")) {
                String[] values = line.substring(1).trim().split("\\s+");
                for (String value : values) {
                    String[] pair = value.split("=", 2);
                    if (pair.length != 2) continue;
                    int parsed = Utils.strToInt(pair[1]);
                    if ("max_mv".equals(pair[0])) maximum = parsed;
                    if ("live_vproc_mv".equals(pair[0])) liveVproc = parsed;
                    if ("live_vsram_mv".equals(pair[0])) liveVsram = parsed;
                }
                continue;
            }

            String[] values = line.split("\\s+");
            if (values.length != 3) continue;
            voltages.add(new OppVoltage(Utils.strToInt(values[0]), Utils.strToInt(values[1]),
                    Utils.strToInt(values[2]), maximum, liveVproc, liveVsram));
        }
        return voltages;
    }

    public static List<String> getVoltageChoices(OppVoltage voltage) {
        List<String> choices = new ArrayList<>();
        int maximum = voltage.getMaximumMillivolts();

        /* Every OPP has the same kernel-validated tuning range. The selected
         * value remains that OPP's stock voltage until the user changes it. */
        for (int mv = MINIMUM_VOLTAGE_MILLIVOLTS; mv <= maximum; mv += 5) {
            choices.add(String.valueOf(mv));
        }
        /* Vendor OPPs and a previously selected value may fall between the
         * convenient 5 mV UI increments. Never hide either one. */
        addVoltageChoice(choices, voltage.getStockMillivolts());
        addVoltageChoice(choices, voltage.getAssignedMillivolts());
        addVoltageChoice(choices, maximum);
        Collections.sort(choices, (left, right) -> Utils.strToInt(left) - Utils.strToInt(right));
        return choices;
    }

    private static void addVoltageChoice(List<String> choices, int millivolts) {
        String value = String.valueOf(millivolts);
        if (!choices.contains(value)) choices.add(value);
    }

    public static void setOppVoltage(int cpu, OppVoltage voltage, int millivolts,
                                     String category, android.content.Context context) {
        String path = getOppVoltagePath(cpu);
        String id = path + ":" + voltage.getFrequency();
        Control.runSetting(Control.write(voltage.getFrequency() + " " + millivolts, path),
                category, id, context);
    }

    /** Restore one OPP to the immutable vendor voltage captured by the driver. */
    public static void resetOppVoltage(int cpu, OppVoltage voltage, String category,
                                       android.content.Context context) {
        setOppVoltage(cpu, voltage, 0, category, context);
    }
}
