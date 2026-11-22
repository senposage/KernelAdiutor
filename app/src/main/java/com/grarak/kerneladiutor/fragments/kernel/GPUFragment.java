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
package com.grarak.kerneladiutor.fragments.kernel;

import android.app.Activity;
import android.os.AsyncTask;

import com.grarak.kerneladiutor.R;
import com.grarak.kerneladiutor.fragments.ApplyOnBootFragment;
import com.grarak.kerneladiutor.fragments.BaseFragment;
import com.grarak.kerneladiutor.fragments.recyclerview.RecyclerViewFragment;
import com.grarak.kerneladiutor.utils.Utils;
import com.grarak.kerneladiutor.utils.kernel.gpu.AdrenoIdler;
import com.grarak.kerneladiutor.utils.kernel.gpu.GPUFreq;
import com.grarak.kerneladiutor.utils.kernel.gpu.SimpleGPU;
import com.grarak.kerneladiutor.utils.kernel.gpu.SuezGPU;
import com.grarak.kerneladiutor.utils.kernel.gpu.T227GPU;
import com.grarak.kerneladiutor.views.dialog.Dialog;
import com.grarak.kerneladiutor.views.recyclerview.CardView;
import com.grarak.kerneladiutor.views.recyclerview.DescriptionView;
import com.grarak.kerneladiutor.views.recyclerview.RecyclerViewItem;
import com.grarak.kerneladiutor.views.recyclerview.SeekBarView;
import com.grarak.kerneladiutor.views.recyclerview.SelectView;
import com.grarak.kerneladiutor.views.recyclerview.SwitchView;
import com.grarak.kerneladiutor.views.recyclerview.TitleView;
import com.grarak.kerneladiutor.views.recyclerview.XYGraphView;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Created by willi on 12.05.16.
 */
public class GPUFragment extends RecyclerViewFragment {

    private GPUFreq mGPUFreq;

    private XYGraphView m2dCurFreq;
    private XYGraphView mCurFreq;
    private SelectView mSuezMaximumFrequency;
    private int mSuezCurrentFrequency;
    private int mSuezMaximumFrequencyValue;
    private List<Integer> mSuezFrequencies;
    private SelectView mT227MaximumFrequency;
    private int mT227CurrentFrequency;
    private int mT227MaximumFrequencyValue;
    private List<Integer> mT227Frequencies;
    private DescriptionView mT227VcoreView;
    private volatile T227GPU.VcoreState mT227Vcore;
    private boolean mT227VcoreChanging;
    private final List<SuezVoltageView> mSuezVoltageViews = new ArrayList<>();

    private PathReaderFragment mGPUGovernorTunableFragment;

    private static class SuezVoltageView {
        private final SelectView mView;
        private final SuezGPU.OppVoltage mVoltage;

        private SuezVoltageView(SelectView view, SuezGPU.OppVoltage voltage) {
            mView = view;
            mVoltage = voltage;
        }
    }

    @Override
    protected BaseFragment getForegroundFragment() {
        return mGPUGovernorTunableFragment = new PathReaderFragment();
    }

    @Override
    protected void init() {
        super.init();

        mGPUFreq = GPUFreq.getInstance();
        addViewPagerFragment(ApplyOnBootFragment.newInstance(this));
    }

    @Override
    protected void addItems(List<RecyclerViewItem> items) {
        if (T227GPU.supported()) {
            t227GpuInit(items);
            return;
        }
        if (SuezGPU.supported()) {
            suezGpuInit(items);
            return;
        }
        freqInit(items);
        governorInit(items);
        if (SimpleGPU.supported()) {
            simpleGpuInit(items);
        }
        if (AdrenoIdler.supported()) {
            adrenoIdlerInit(items);
        }
    }

    private void t227GpuInit(List<RecyclerViewItem> items) {
        CardView card = new CardView();
        card.setTitle(getString(R.string.gpu));

        mCurFreq = new XYGraphView();
        mCurFreq.setTitle(getString(R.string.gpu_freq));
        card.addItem(mCurFreq);

        mT227MaximumFrequency = new SelectView();
        mT227MaximumFrequency.setTitle(getString(R.string.gpu_max_freq));
        mT227MaximumFrequency.setSummary("Persistent MTK GPU DVFS ceiling.");
        mT227Frequencies = T227GPU.getFrequencies();
        List<String> entries = new ArrayList<>();
        for (int frequency : mT227Frequencies) {
            entries.add((frequency / 1000) + getString(R.string.mhz));
        }
        mT227MaximumFrequency.setItems(entries);
        int maximum = T227GPU.getMaximumFrequency();
        if (maximum > 0) mT227MaximumFrequency.setItem((maximum / 1000) + getString(R.string.mhz));
        mT227MaximumFrequency.setOnItemSelected((selectView, position, item) ->
                T227GPU.setMaximumFrequency(mT227Frequencies.get(position), getActivity()));
        card.addItem(mT227MaximumFrequency);

        mT227VcoreView = null;
        if (T227GPU.hasVcoreControl()) {
            mT227Vcore = T227GPU.readVcore();
            mT227VcoreView = new DescriptionView();
            mT227VcoreView.setTitle(getString(R.string.t227_gpu_vcore));
            mT227VcoreView.setOnItemClickListener(item -> showT227VcorePicker());
            updateT227VcoreView();
            card.addItem(mT227VcoreView);
        }
        items.add(card);
    }

    private void showT227VcorePicker() {
        Activity activity = getActivity();
        T227GPU.VcoreState state = mT227Vcore;
        if (activity == null || mT227VcoreChanging || state == null || !state.canAdjust()) {
            return;
        }

        List<Integer> voltages = T227GPU.getVcoreVoltages();
        String[] entries = new String[voltages.size()];
        entries[0] = getString(R.string.t227_gpu_vcore_stock);
        for (int i = 1; i < entries.length; i++) {
            entries[i] = formatVcoreVoltage(voltages.get(i));
        }
        new Dialog(activity).setTitle(R.string.t227_gpu_vcore)
                .setItems(entries, (dialog, index) -> applyT227Vcore(activity, voltages.get(index)))
                .show();
    }

    private void applyT227Vcore(Activity activity, int microvolts) {
        if (mT227VcoreChanging) return;
        mT227VcoreChanging = true;
        updateT227VcoreView();

        AsyncTask.THREAD_POOL_EXECUTOR.execute(() -> {
            T227GPU.VcoreState result = T227GPU.setVcore(microvolts);
            mT227Vcore = result == null ? T227GPU.readVcore() : result;
            activity.runOnUiThread(() -> {
                mT227VcoreChanging = false;
                if (!isAdded()) return;
                updateT227VcoreView();
                if (result == null) Utils.toast(R.string.t227_gpu_vcore_write_failed, activity);
            });
        });
    }

    private String formatVcoreVoltage(int microvolts) {
        if (microvolts <= 0) return getString(R.string.t227_gpu_vcore_unavailable);
        return getString(R.string.t227_gpu_vcore_mv,
                NumberFormat.getNumberInstance().format(microvolts / 1000.0));
    }

    private void updateT227VcoreView() {
        if (mT227VcoreView == null) return;
        if (mT227VcoreChanging) {
            mT227VcoreView.setSummary(getString(R.string.applying));
            return;
        }
        T227GPU.VcoreState state = mT227Vcore;
        if (state == null) {
            mT227VcoreView.setSummary(getString(R.string.t227_gpu_vcore_unavailable));
            return;
        }
        String live = formatVcoreVoltage(state.liveUv);
        if (state.activeGear >= 0) {
            live = getString(R.string.t227_gpu_vcore_live_gear, live, state.activeGear);
        }
        mT227VcoreView.setSummary(getString(R.string.t227_gpu_vcore_readback,
                formatVcoreVoltage(state.programmedUv), live));
    }

    private void suezGpuInit(List<RecyclerViewItem> items) {
        mSuezVoltageViews.clear();
        CardView card = new CardView();
        card.setTitle(getString(R.string.gpu));

        mCurFreq = new XYGraphView();
        mCurFreq.setTitle(getString(R.string.gpu_freq));
        card.addItem(mCurFreq);

        mSuezMaximumFrequency = new SelectView();
        mSuezMaximumFrequency.setTitle(getString(R.string.gpu_max_freq));
        mSuezMaximumFrequency.setSummary("Dynamic ceiling; MTK GPU DVFS remains enabled.");
        mSuezFrequencies = SuezGPU.getFrequencies();
        List<String> entries = new ArrayList<>();
        for (int frequency : mSuezFrequencies) {
            entries.add((frequency / 1000) + getString(R.string.mhz));
        }
        mSuezMaximumFrequency.setItems(entries);
        int maximum = SuezGPU.getMaximumFrequency();
        if (maximum > 0) {
            mSuezMaximumFrequency.setItem((maximum / 1000) + getString(R.string.mhz));
        }
        mSuezMaximumFrequency.setOnItemSelected((selectView, position, item) ->
                SuezGPU.setMaximumFrequency(mSuezFrequencies.get(position), getActivity()));
        card.addItem(mSuezMaximumFrequency);

        items.add(card);
        suezGpuVoltageInit(items);
    }

    private void suezGpuVoltageInit(List<RecyclerViewItem> items) {
        if (!SuezGPU.hasOppVoltageControl()) return;

        CardView card = new CardView();
        card.setTitle("GPU OPP voltage");

        List<SuezGPU.OppVoltage> voltages = SuezGPU.getOppVoltages();
        if (voltages.isEmpty()) return;

        SuezGPU.OppVoltage live = voltages.get(0);
        DescriptionView rail = new DescriptionView();
        rail.setTitle("Live rail readback");
        rail.setSummary("VGPU " + live.getLiveMillivolts() + getString(R.string.mv)
                + " · kernel limit " + live.getMaximumMillivolts() + getString(R.string.mv));
        card.addItem(rail);

        DescriptionView reset = new DescriptionView();
        reset.setTitle("Restore all stock voltages");
        reset.setSummary("Resets every GPU OPP to its PTP-calibrated vendor value.");
        reset.setOnItemClickListener(item -> {
            for (SuezGPU.OppVoltage voltage : SuezGPU.getOppVoltages()) {
                SuezGPU.resetOppVoltage(voltage, getActivity());
            }
            resetVisibleSuezVoltageViews();
        });
        card.addItem(reset);

        Collections.sort(voltages, new Comparator<SuezGPU.OppVoltage>() {
            @Override
            public int compare(SuezGPU.OppVoltage left, SuezGPU.OppVoltage right) {
                return right.getFrequency() - left.getFrequency();
            }
        });
        for (final SuezGPU.OppVoltage voltage : voltages) {
            SelectView view = new SelectView();
            view.setTitle((voltage.getFrequency() / 1000) + getString(R.string.mhz));
            view.setSummary(suezVoltageSummary(voltage, voltage.getAssignedMillivolts()));
            view.setItems(SuezGPU.getVoltageChoices(voltage));
            view.setItem(String.valueOf(voltage.getAssignedMillivolts()));
            view.setOnItemSelected((selectView, position, item) -> {
                int millivolts = Utils.strToInt(item);
                selectView.setSummary(suezVoltageSummary(voltage, millivolts));
                SuezGPU.setOppVoltage(voltage, millivolts, getActivity());
            });
            mSuezVoltageViews.add(new SuezVoltageView(view, voltage));
            card.addItem(view);
        }
        items.add(card);
    }

    private void resetVisibleSuezVoltageViews() {
        for (SuezVoltageView voltageView : mSuezVoltageViews) {
            int stock = voltageView.mVoltage.getStockMillivolts();
            voltageView.mView.setItem(String.valueOf(stock));
            voltageView.mView.setSummary(suezVoltageSummary(voltageView.mVoltage, stock));
        }
    }

    private String suezVoltageSummary(SuezGPU.OppVoltage voltage, int assignedMillivolts) {
        String summary = "Stock " + voltage.getStockMillivolts() + getString(R.string.mv);
        if (assignedMillivolts != voltage.getStockMillivolts()) {
            summary += " · selected " + assignedMillivolts + getString(R.string.mv);
        }
        return summary + " · inactive OPPs apply on their next transition";
    }

    private void freqInit(List<RecyclerViewItem> items) {
        CardView freqCard = new CardView();
        freqCard.setTitle(getString(R.string.frequencies));

        if (mGPUFreq.has2dCurFreq() && mGPUFreq.get2dAvailableFreqs() != null) {
            m2dCurFreq = new XYGraphView();
            m2dCurFreq.setTitle(getString(R.string.gpu_2d_freq));
            freqCard.addItem(m2dCurFreq);
        }

        if (mGPUFreq.hasCurFreq() && mGPUFreq.getAvailableFreqs() != null) {
            mCurFreq = new XYGraphView();
            mCurFreq.setTitle(getString(R.string.gpu_freq));
            freqCard.addItem(mCurFreq);
        }

        if (mGPUFreq.has2dMaxFreq() && mGPUFreq.get2dAvailableFreqs() != null) {
            SelectView max2dFreq = new SelectView();
            max2dFreq.setTitle(getString(R.string.gpu_2d_max_freq));
            max2dFreq.setSummary(getString(R.string.gpu_2d_max_freq_summary));
            max2dFreq.setItems(mGPUFreq.get2dAdjustedFreqs(getActivity()));
            max2dFreq.setItem((mGPUFreq.get2dMaxFreq() / 1000000) + getString(R.string.mhz));
            max2dFreq.setOnItemSelected((selectView, position, item)
                    -> mGPUFreq.set2dMaxFreq(mGPUFreq.get2dAvailableFreqs().get(position), getActivity()));

            freqCard.addItem(max2dFreq);
        }

        if (mGPUFreq.hasMaxFreq() && mGPUFreq.getAvailableFreqs() != null) {
            SelectView maxFreq = new SelectView();
            maxFreq.setTitle(getString(R.string.gpu_max_freq));
            maxFreq.setSummary(getString(R.string.gpu_max_freq_summary));
            maxFreq.setItems(mGPUFreq.getAdjustedFreqs(getActivity()));
            maxFreq.setItem((mGPUFreq.getMaxFreq() / mGPUFreq.getMaxFreqOffset()) + getString(R.string.mhz));
            maxFreq.setOnItemSelected((selectView, position, item)
                    -> mGPUFreq.setMaxFreq(mGPUFreq.getAvailableFreqs().get(position), getActivity()));

            freqCard.addItem(maxFreq);
        }

        if (mGPUFreq.hasMinFreq() && mGPUFreq.getAvailableFreqs() != null) {
            SelectView minFreq = new SelectView();
            minFreq.setTitle(getString(R.string.gpu_min_freq));
            minFreq.setSummary(getString(R.string.gpu_min_freq_summary));
            minFreq.setItems(mGPUFreq.getAdjustedFreqs(getActivity()));
            minFreq.setItem((mGPUFreq.getMinFreq() / mGPUFreq.getMinFreqOffset()) + getString(R.string.mhz));
            minFreq.setOnItemSelected((selectView, position, item)
                    -> mGPUFreq.setMinFreq(mGPUFreq.getAvailableFreqs().get(position), getActivity()));

            freqCard.addItem(minFreq);
        }

        if (freqCard.size() > 0) {
            items.add(freqCard);
        }
    }

    private void governorInit(List<RecyclerViewItem> items) {
        if (mGPUFreq.has2dGovernor()) {
            SelectView governor2d = new SelectView();
            governor2d.setTitle(getString(R.string.gpu_2d_governor));
            governor2d.setSummary(getString(R.string.gpu_2d_governor_summary));
            governor2d.setItems(mGPUFreq.get2dAvailableGovernors());
            governor2d.setItem(mGPUFreq.get2dGovernor());
            governor2d.setOnItemSelected((selectView, position, item)
                    -> mGPUFreq.set2dGovernor(item, getActivity()));

            items.add(governor2d);
        }

        if (mGPUFreq.hasGovernor()) {
            SelectView governor = new SelectView();
            governor.setTitle(getString(R.string.gpu_governor));
            governor.setSummary(getString(R.string.gpu_governor_summary));
            governor.setItems(mGPUFreq.getAvailableGovernors());
            governor.setItem(mGPUFreq.getGovernor());
            governor.setOnItemSelected((selectView, position, item)
                    -> mGPUFreq.setGovernor(item, getActivity()));

            items.add(governor);

            if (mGPUFreq.hasTunables(governor.getValue())) {
                DescriptionView tunables = new DescriptionView();
                tunables.setTitle(getString(R.string.gpu_governor_tunables));
                tunables.setSummary(getString(R.string.governor_tunables_summary));
                tunables.setOnItemClickListener(item -> {
                    String governor1 = mGPUFreq.getGovernor();
                    setForegroundText(governor1);
                    mGPUGovernorTunableFragment.setError(getString(R.string.tunables_error, governor1));
                    mGPUGovernorTunableFragment.setPath(mGPUFreq.getTunables(mGPUFreq.getGovernor()),
                            ApplyOnBootFragment.GPU);
                    showForeground();
                });

                items.add(tunables);
            }
        }
    }

    private void simpleGpuInit(List<RecyclerViewItem> items) {
        List<RecyclerViewItem> simpleGpu = new ArrayList<>();
        TitleView title = new TitleView();
        title.setText(getString(R.string.simple_gpu_algorithm));

        if (SimpleGPU.hasSimpleGpuEnable()) {
            SwitchView enable = new SwitchView();
            enable.setTitle(getString(R.string.simple_gpu_algorithm));
            enable.setSummary(getString(R.string.simple_gpu_algorithm_summary));
            enable.setChecked(SimpleGPU.isSimpleGpuEnabled());
            enable.addOnSwitchListener((switchView, isChecked)
                    -> SimpleGPU.enableSimpleGpu(isChecked, getActivity()));

            simpleGpu.add(enable);
        }

        if (SimpleGPU.hasSimpleGpuLaziness()) {
            SeekBarView laziness = new SeekBarView();
            laziness.setTitle(getString(R.string.laziness));
            laziness.setSummary(getString(R.string.laziness_summary));
            laziness.setMax(10);
            laziness.setProgress(SimpleGPU.getSimpleGpuLaziness());
            laziness.setOnSeekBarListener(new SeekBarView.OnSeekBarListener() {
                @Override
                public void onMove(SeekBarView seekBarView, int position, String value) {
                }

                @Override
                public void onStop(SeekBarView seekBarView, int position, String value) {
                    SimpleGPU.setSimpleGpuLaziness(position, getActivity());
                }
            });

            simpleGpu.add(laziness);
        }

        if (SimpleGPU.hasSimpleGpuRampThreshold()) {
            SeekBarView rampThreshold = new SeekBarView();
            rampThreshold.setTitle(getString(R.string.ramp_thresold));
            rampThreshold.setSummary(getString(R.string.ramp_thresold_summary));
            rampThreshold.setMax(10);
            rampThreshold.setProgress(SimpleGPU.getSimpleGpuRampThreshold());
            rampThreshold.setOnSeekBarListener(new SeekBarView.OnSeekBarListener() {
                @Override
                public void onMove(SeekBarView seekBarView, int position, String value) {
                }

                @Override
                public void onStop(SeekBarView seekBarView, int position, String value) {
                    SimpleGPU.setSimpleGpuRampThreshold(position, getActivity());
                }
            });

            simpleGpu.add(rampThreshold);
        }

        if (simpleGpu.size() > 0) {
            items.add(title);
            items.addAll(simpleGpu);
        }
    }

    private void adrenoIdlerInit(List<RecyclerViewItem> items) {
        List<RecyclerViewItem> adrenoIdler = new ArrayList<>();
        TitleView title = new TitleView();
        title.setText(getString(R.string.adreno_idler));

        if (AdrenoIdler.hasAdrenoIdlerEnable()) {
            SwitchView enable = new SwitchView();
            enable.setTitle(getString(R.string.adreno_idler));
            enable.setSummary(getString(R.string.adreno_idler_summary));
            enable.setChecked(AdrenoIdler.isAdrenoIdlerEnabled());
            enable.addOnSwitchListener((switchView, isChecked)
                    -> AdrenoIdler.enableAdrenoIdler(isChecked, getActivity()));

            adrenoIdler.add(enable);
        }

        if (AdrenoIdler.hasAdrenoIdlerDownDiff()) {
            SeekBarView downDiff = new SeekBarView();
            downDiff.setTitle(getString(R.string.down_differential));
            downDiff.setSummary(getString(R.string.down_differential_summary));
            downDiff.setMax(99);
            downDiff.setProgress(AdrenoIdler.getAdrenoIdlerDownDiff());
            downDiff.setOnSeekBarListener(new SeekBarView.OnSeekBarListener() {
                @Override
                public void onMove(SeekBarView seekBarView, int position, String value) {
                }

                @Override
                public void onStop(SeekBarView seekBarView, int position, String value) {
                    AdrenoIdler.setAdrenoIdlerDownDiff(position, getActivity());
                }
            });

            adrenoIdler.add(downDiff);
        }

        if (AdrenoIdler.hasAdrenoIdlerIdleWait()) {
            SeekBarView idleWait = new SeekBarView();
            idleWait.setTitle(getString(R.string.idle_wait));
            idleWait.setSummary(getString(R.string.idle_wait_summary));
            idleWait.setMax(99);
            idleWait.setProgress(AdrenoIdler.getAdrenoIdlerIdleWait());
            idleWait.setOnSeekBarListener(new SeekBarView.OnSeekBarListener() {
                @Override
                public void onMove(SeekBarView seekBarView, int position, String value) {
                }

                @Override
                public void onStop(SeekBarView seekBarView, int position, String value) {
                    AdrenoIdler.setAdrenoIdlerIdleWait(position, getActivity());
                }
            });

            adrenoIdler.add(idleWait);
        }

        if (AdrenoIdler.hasAdrenoIdlerIdleWorkload()) {
            SeekBarView idleWorkload = new SeekBarView();
            idleWorkload.setTitle(getString(R.string.workload));
            idleWorkload.setSummary(getString(R.string.workload_summary));
            idleWorkload.setMax(10);
            idleWorkload.setMin(1);
            idleWorkload.setProgress(AdrenoIdler.getAdrenoIdlerIdleWorkload() - 1);
            idleWorkload.setOnSeekBarListener(new SeekBarView.OnSeekBarListener() {
                @Override
                public void onMove(SeekBarView seekBarView, int position, String value) {
                }

                @Override
                public void onStop(SeekBarView seekBarView, int position, String value) {
                    AdrenoIdler.setAdrenoIdlerIdleWorkload(position + 1, getActivity());
                }
            });

            adrenoIdler.add(idleWorkload);
        }

        if (adrenoIdler.size() > 0) {
            items.add(title);
            items.addAll(adrenoIdler);
        }
    }

    private Integer m2dFreq;
    private List<Integer> m2dFreqs;
    private Integer mBusy;
    private Integer mFreq;
    private List<Integer> mFreqs;

    @Override
    protected void refreshThread() {
        super.refreshThread();

        if (SuezGPU.supported()) {
            mSuezCurrentFrequency = SuezGPU.getCurrentFrequency();
            mSuezMaximumFrequencyValue = SuezGPU.getMaximumFrequency();
            mSuezFrequencies = SuezGPU.getFrequencies();
            return;
        }
        if (T227GPU.supported()) {
            mT227CurrentFrequency = T227GPU.getCurrentFrequency();
            mT227MaximumFrequencyValue = T227GPU.getMaximumFrequency();
            if (mT227VcoreView != null) mT227Vcore = T227GPU.readVcore();
        }

        m2dFreq = mGPUFreq.get2dCurFreq();
        m2dFreqs = mGPUFreq.get2dAvailableFreqs();
        mBusy = mGPUFreq.hasBusy() ? mGPUFreq.getBusy() : null;
        mFreq = mGPUFreq.getCurFreq();
        mFreqs = mGPUFreq.getAvailableFreqs();

        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    @Override
    protected void refresh() {
        super.refresh();

        if (SuezGPU.supported()) {
            if (mCurFreq != null && mSuezCurrentFrequency > 0 && mSuezFrequencies != null
                    && !mSuezFrequencies.isEmpty()) {
                int maximum = mSuezFrequencies.get(mSuezFrequencies.size() - 1);
                mCurFreq.setText((mSuezCurrentFrequency / 1000) + getString(R.string.mhz));
                mCurFreq.addPercentage(Math.round(Math.min(100f,
                        (float) mSuezCurrentFrequency / maximum * 100f)));
            }
            if (mSuezMaximumFrequency != null && mSuezMaximumFrequencyValue > 0) {
                mSuezMaximumFrequency.setItem((mSuezMaximumFrequencyValue / 1000)
                        + getString(R.string.mhz));
            }
            return;
        }
        if (T227GPU.supported()) {
            if (mCurFreq != null && mT227CurrentFrequency > 0) {
                int maximum = mT227Frequencies == null || mT227Frequencies.isEmpty() ? 0
                        : mT227Frequencies.get(mT227Frequencies.size() - 1);
                mCurFreq.setText((mT227CurrentFrequency / 1000) + getString(R.string.mhz));
                if (maximum > 0) {
                    mCurFreq.addPercentage(Math.round(Math.min(100f,
                            (float) mT227CurrentFrequency / maximum * 100f)));
                }
            }
            if (mT227MaximumFrequency != null && mT227MaximumFrequencyValue > 0) {
                mT227MaximumFrequency.setItem((mT227MaximumFrequencyValue / 1000)
                        + getString(R.string.mhz));
            }
            updateT227VcoreView();
            return;
        }

        if (m2dCurFreq != null && m2dFreq != null && m2dFreqs != null) {
            float maxFreq = m2dFreqs.get(m2dFreqs.size() - 1);
            m2dCurFreq.setText((m2dFreq / 1000000) + getString(R.string.mhz));
            float per = (float) m2dFreq / maxFreq * 100f;
            m2dCurFreq.addPercentage(Math.round(per > 100 ? 100 : per < 0 ? 0 : per));
        }

        if (mCurFreq != null && mFreq != null && mFreqs != null) {
            int load = -1;
            String text = "";
            if (mBusy != null) {
                load = mBusy;
                load = load > 100 ? 100 : load < 0 ? 0 : load;
                text += load + "% - ";
            }

            int freq = mFreq;
            float maxFreq = mFreqs.get(mFreqs.size() - 1);
            text += freq / mGPUFreq.getCurFreqOffset() + getString(R.string.mhz);
            mCurFreq.setText(text);
            float per = (float) freq / maxFreq * 100f;
            mCurFreq.addPercentage(load >= 0 ? load : Math.round(per > 100 ? 100 : per < 0 ? 0 : per));
        }
    }
}
