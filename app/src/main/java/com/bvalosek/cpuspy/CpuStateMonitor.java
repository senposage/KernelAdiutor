//-----------------------------------------------------------------------------
//
// (C) Brandon Valosek, 2011 <bvalosek@gmail.com>
// (C) Willi Ye, 2015 <williye97@gmail.com>
//
//-----------------------------------------------------------------------------
// Modified by Willi Ye to work with big.LITTLE

package com.bvalosek.cpuspy;

import android.os.SystemClock;
import android.support.annotation.NonNull;
import android.util.SparseArray;

import com.grarak.kerneladiutor.utils.Utils;
import com.grarak.kerneladiutor.utils.kernel.cpu.CPUFreq;
import com.grarak.kerneladiutor.utils.kernel.cpu.SuezDVFS;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * CpuStateMonitor is a class responsible for querying the system and getting
 * the time-in-state information, as well as allowing the user to set/reset
 * offsets to "restart" the state timers
 */
public class CpuStateMonitor {

    private final int mCore;

    private final List<CpuState> mStates = new ArrayList<>();
    private final SparseArray<Long> mOffsets = new SparseArray<>();

    CpuStateMonitor(int core) {
        mCore = core;
    }

    /**
     * exception class
     */
    public static class CpuStateMonitorException extends Exception {
        private CpuStateMonitorException(String s) {
            super(s);
        }
    }

    /**
     * simple struct for states/time
     */
    public static class CpuState implements Comparable<CpuState> {

        private int mFreq = 0;
        private long mDuration = 0;

        /**
         * init with freq and duration
         */
        private CpuState(int a, long b) {
            mFreq = a;
            mDuration = b;
        }

        public int getFreq() {
            return mFreq;
        }

        public long getDuration() {
            return mDuration;
        }

        /**
         * for sorting, compare the freqs
         */
        @Override
        public int compareTo(@NonNull CpuState another) {
            return ((Integer) mFreq).compareTo(another.mFreq);
        }
    }

    /**
     * @return List of CpuState with the offsets applied
     */
    public List<CpuState> getStates() {
        List<CpuState> states = new ArrayList<>();

        /*
         * check for an existing offset, and if it's not too big, subtract it
         * from the duration, otherwise just add it to the return List
         */
        for (CpuState state : mStates) {
            long duration = state.getDuration();
            if (mOffsets.indexOfKey(state.getFreq()) >= 0) {
                long offset = mOffsets.get(state.getFreq());
                if (offset <= duration) {
                    duration -= offset;
                } else {
                    /*
                     * offset > duration implies our offsets are now invalid, so
                     * clear and recall this function
                     */
                    mOffsets.clear();
                    return getStates();
                }
            }

            states.add(new CpuState(state.getFreq(), duration));
        }

        return states;
    }

    /**
     * @return Sum of all state durations including deep sleep, accounting for
     * offsets
     */
    public long getTotalStateTime() {
        long sum = 0;

        for (CpuState state : mStates) {
            sum += state.getDuration();
        }

        return sum;
    }

    /**
     * @return Map of freq->duration of all the offsets
     */
    SparseArray<Long> getOffsets() {
        return mOffsets;
    }

    /**
     * Sets the offset map (freq->duration offset)
     */
    void setOffsets(SparseArray<Long> offsets) {
        mOffsets.clear();
        for (int i = 0; i < offsets.size(); i++) {
            mOffsets.put(offsets.keyAt(i), offsets.valueAt(i));
        }
    }

    /**
     * Updates the current time in states and then sets the offset map to the
     * current duration, effectively "zeroing out" the timers
     */
    public void setOffsets() throws CpuStateMonitorException {
        mOffsets.clear();
        updateStates();

        for (CpuState state : mStates) {
            mOffsets.put(state.getFreq(), state.getDuration());
        }
    }

    /**
     * removes state offsets
     */
    public void removeOffsets() {
        mOffsets.clear();
    }

    /**
     * list of all the CPU frequency states, which contains both a
     * frequency and a duration (time spent in that state
     */
    public void updateStates() throws CpuStateMonitorException {
        CPUFreq cpuFreq = CPUFreq.getInstance();

        mStates.clear();
        try {
            /* HPS can move the BIG cpufreq policy from cpu2 to cpu3.  Read
             * residency from the live policy rather than the fixed logical
             * cluster representative used by the UI. */
            int statsCore = SuezDVFS.supported() ? SuezDVFS.getStatsCpu(mCore) : mCore;
            String file;
            if (SuezDVFS.supported() && SuezDVFS.hasTimeInState(mCore)) {
                file = SuezDVFS.getTimeInStatePath(mCore);
            } else if (Utils.existFile(Utils.strFormat(CPUFreq.TIME_STATE, statsCore))) {
                file = Utils.strFormat(CPUFreq.TIME_STATE, statsCore);
            } else {
                file = Utils.strFormat(CPUFreq.TIME_STATE_2, statsCore);
            }
            boolean offline = !SuezDVFS.supported() && cpuFreq.isOffline(statsCore);
            if (offline) {
                cpuFreq.onlineCpu(statsCore, true, false, null);
            }
            String states = Utils.readFile(file);
            if (offline) {
                cpuFreq.onlineCpu(statsCore, false, false, null);
            }
            if (states == null || states.trim().isEmpty()) {
                throw new CpuStateMonitorException("Problem opening time-in-states file");
            }
            readInStates(states.split("\\r?\\n"));
        } catch (Exception e) {
            /* A parked MTK cluster deliberately has no cpufreq policy sysfs.
             * Keep its full OPP table visible with zero residency instead of
             * falsely reporting that the frequency table cannot be loaded. */
            if (SuezDVFS.supported() && SuezDVFS.hasOppVoltageControl()) {
                for (SuezDVFS.OppVoltage opp : SuezDVFS.getOppVoltages(mCore)) {
                    mStates.add(new CpuState(opp.getFrequency(), 0));
                }
                if (!mStates.isEmpty()) {
                    Collections.sort(mStates, Collections.reverseOrder());
                    return;
                }
            }
            throw new CpuStateMonitorException("Problem opening time-in-states file");
        }

        /*
         * deep sleep time determined by difference between elapsed (total) boot
         * time and the system uptime (awake)
         */
        /* BIG-cluster residency is deliberately paused while HPS has that
         * cluster parked. System deep sleep is not a BIG OPP, so including
         * it would distort the card's uptime and frequency percentages. */
        if (!SuezDVFS.supported() || !SuezDVFS.isBigCpu(mCore)) {
            long sleepTime = (SystemClock.elapsedRealtime() - SystemClock.uptimeMillis()) / 10;
            mStates.add(new CpuState(0, sleepTime));
        }

        Collections.sort(mStates, Collections.reverseOrder());
    }

    /**
     * read from a provided BufferedReader the state lines into the States
     * member field
     */
    private void readInStates(String[] states) throws CpuStateMonitorException {
        try {
            // split open line and convert to Integers
            for (String line : states) {
                String[] nums = line.split(" ");
                mStates.add(new CpuState(Utils.strToInt(nums[0]), Utils.strToLong(nums[1])));
            }
        } catch (Exception e) {
            throw new CpuStateMonitorException("Problem processing time-in-states file");
        }
    }

}
