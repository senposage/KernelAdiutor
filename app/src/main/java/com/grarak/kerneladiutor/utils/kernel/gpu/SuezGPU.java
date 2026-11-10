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

/** MT8173/PowerVR GX6250 dynamic ceiling controls. */
public final class SuezGPU {

    private static final String OPP_DUMP = "/proc/gpufreq/gpufreq_opp_dump";
    private static final String OPP_VOLTAGE = "/proc/gpufreq/gpufreq_opp_voltage";
    private static final String VAR_DUMP = "/proc/gpufreq/gpufreq_var_dump";
    private static final String MAX_FREQ = "/proc/gpufreq/gpufreq_max_freq";
    private static final Pattern FREQUENCY = Pattern.compile("freq\\s*=\\s*(\\d+)");
    private static final Pattern CURRENT = Pattern.compile("g_cur_gpu_freq\\s*=\\s*(\\d+)");

    public static final class OppVoltage {
        private final int mFrequency;
        private final int mAssignedMillivolts;
        private final int mStockMillivolts;
        private final int mMaximumMillivolts;
        private final int mLiveMillivolts;

        private OppVoltage(int frequency, int assignedMillivolts, int stockMillivolts,
                           int maximumMillivolts, int liveMillivolts) {
            mFrequency = frequency;
            mAssignedMillivolts = assignedMillivolts;
            mStockMillivolts = stockMillivolts;
            mMaximumMillivolts = maximumMillivolts;
            mLiveMillivolts = liveMillivolts;
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

        public int getLiveMillivolts() {
            return mLiveMillivolts;
        }
    }

    private SuezGPU() {
    }

    public static boolean supported() {
        return Utils.existFile(OPP_DUMP) && Utils.existFile(VAR_DUMP)
                && Utils.existFile(MAX_FREQ);
    }

    public static boolean hasOppVoltageControl() {
        return Utils.existFile(OPP_VOLTAGE);
    }

    public static List<Integer> getFrequencies() {
        List<Integer> frequencies = new ArrayList<>();
        String dump = Utils.readFile(OPP_DUMP);
        if (dump == null) return frequencies;

        Matcher matcher = FREQUENCY.matcher(dump);
        while (matcher.find()) {
            int frequency = Utils.strToInt(matcher.group(1));
            if (frequency > 0 && !frequencies.contains(frequency)) {
                frequencies.add(frequency);
            }
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
        return Utils.strToInt(Utils.readFile(MAX_FREQ));
    }

    /**
     * This is a ceiling only.  The MTK GPU DVFS governor remains enabled and
     * can continue to select every OPP below the chosen limit.
     */
    public static void setMaximumFrequency(int frequency, Context context) {
        Control.runSetting(Control.write(String.valueOf(frequency), MAX_FREQ),
                ApplyOnBootFragment.GPU, MAX_FREQ, context);
    }

    public static List<OppVoltage> getOppVoltages() {
        List<OppVoltage> voltages = new ArrayList<>();
        String dump = Utils.readFile(OPP_VOLTAGE);
        if (dump == null) return voltages;

        int maximum = 0;
        int live = 0;
        String[] lines = dump.split("\\n");
        for (String line : lines) {
            line = line.trim();
            if (!line.startsWith("#")) continue;
            String[] values = line.substring(1).trim().split("\\s+");
            for (String value : values) {
                String[] pair = value.split("=", 2);
                if (pair.length != 2) continue;
                if ("max_mv".equals(pair[0])) maximum = Utils.strToInt(pair[1]);
                if ("live_vgpu_mv".equals(pair[0])) live = Utils.strToInt(pair[1]);
            }
        }

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            String[] values = line.split("\\s+");
            if (values.length != 3) continue;
            voltages.add(new OppVoltage(Utils.strToInt(values[0]), Utils.strToInt(values[1]),
                    Utils.strToInt(values[2]), maximum, live));
        }
        return voltages;
    }

    public static List<String> getVoltageChoices(OppVoltage voltage) {
        List<String> choices = new ArrayList<>();
        for (int mv = 700; mv <= voltage.getMaximumMillivolts(); mv += 5) {
            choices.add(String.valueOf(mv));
        }
        addVoltageChoice(choices, voltage.getStockMillivolts());
        addVoltageChoice(choices, voltage.getAssignedMillivolts());
        addVoltageChoice(choices, voltage.getMaximumMillivolts());
        Collections.sort(choices, (left, right) -> Utils.strToInt(left) - Utils.strToInt(right));
        return choices;
    }

    private static void addVoltageChoice(List<String> choices, int millivolts) {
        String value = String.valueOf(millivolts);
        if (millivolts > 0 && !choices.contains(value)) choices.add(value);
    }

    public static void setOppVoltage(OppVoltage voltage, int millivolts, Context context) {
        String id = OPP_VOLTAGE + ":" + voltage.getFrequency();
        Control.runSetting(Control.write(voltage.getFrequency() + " " + millivolts, OPP_VOLTAGE),
                ApplyOnBootFragment.GPU, id, context);
    }

    public static void resetOppVoltage(OppVoltage voltage, Context context) {
        setOppVoltage(voltage, 0, context);
    }
}
