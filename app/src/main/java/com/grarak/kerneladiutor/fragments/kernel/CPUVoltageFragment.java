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

import android.os.Bundle;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.InputType;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.grarak.kerneladiutor.R;
import com.grarak.kerneladiutor.fragments.ApplyOnBootFragment;
import com.grarak.kerneladiutor.fragments.BaseFragment;
import com.grarak.kerneladiutor.fragments.recyclerview.RecyclerViewFragment;
import com.grarak.kerneladiutor.utils.Utils;
import com.grarak.kerneladiutor.utils.kernel.cpu.SuezDVFS;
import com.grarak.kerneladiutor.utils.kernel.cpuvoltage.Voltage;
import com.grarak.kerneladiutor.views.recyclerview.CardView;
import com.grarak.kerneladiutor.views.recyclerview.DescriptionView;
import com.grarak.kerneladiutor.views.recyclerview.GenericSelectView;
import com.grarak.kerneladiutor.views.recyclerview.RecyclerViewItem;
import com.grarak.kerneladiutor.views.recyclerview.SelectView;
import com.grarak.kerneladiutor.views.recyclerview.SwitchView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * Created by willi on 07.05.16.
 */
public class CPUVoltageFragment extends RecyclerViewFragment {

    private Voltage mVoltage;
    private boolean mSuezVoltage;

    private List<GenericSelectView> mVoltages = new ArrayList<>();
    private List<SuezVoltageView> mSuezVoltageViews = new ArrayList<>();

    private static class SuezVoltageView {
        private final SelectView mView;
        private final SuezDVFS.OppVoltage mVoltage;

        private SuezVoltageView(SelectView view, SuezDVFS.OppVoltage voltage) {
            mView = view;
            mVoltage = voltage;
        }
    }

    @Override
    public int getSpanCount() {
        if (SuezDVFS.hasOppVoltageControl()) {
            /* Keep LITTLE and big OPP cards in their own fixed columns. */
            return 2;
        }
        return super.getSpanCount() + 2;
    }

    @Override
    protected void init() {
        super.init();

        mVoltage = Voltage.getInstance();
        mSuezVoltage = SuezDVFS.hasOppVoltageControl();
        addViewPagerFragment(ApplyOnBootFragment.newInstance(this));
        if (!mSuezVoltage) {
            addViewPagerFragment(GlobalOffsetFragment.newInstance(this));
        }
    }

    @Override
    protected void addItems(List<RecyclerViewItem> items) {
        mVoltages.clear();
        mSuezVoltageViews.clear();

        if (mSuezVoltage) {
            DescriptionView reset = new DescriptionView();
            reset.setTitle("Restore all stock voltages");
            reset.setSummary("Resets every LITTLE and big OPP to its vendor default.");
            reset.setFullSpan(true);
            reset.setOnItemClickListener(item -> {
                resetSuezVoltages(0);
                resetSuezVoltages(2);
                /* Keep the populated cards in place. Clearing/reloading this
                 * grid races the asynchronous root writes and can leave a
                 * blank page. */
                resetVisibleSuezVoltageViews();
            });
            items.add(reset);
            addSuezLiveRailReadback(items);
            addSuezVoltageItems(items, 0, "LITTLE");
            addSuezVoltageItems(items, 2, "big");
            return;
        }

        if (mVoltage.hasOverrideVmin()) {
            SwitchView overrideVmin = new SwitchView();
            overrideVmin.setTitle(getString(R.string.override_vmin));
            overrideVmin.setSummary(getString(R.string.override_vmin_summary));
            overrideVmin.setChecked(mVoltage.isOverrideVminEnabled());
            overrideVmin.setFullSpan(true);
            overrideVmin.addOnSwitchListener((switchView, isChecked)
                    -> mVoltage.enableOverrideVmin(isChecked, getActivity()));

            items.add(overrideVmin);
        }

        List<String> freqs = mVoltage.getFreqs();
        List<String> voltages = mVoltage.getVoltages();
        if (freqs != null && voltages != null && freqs.size() == voltages.size()) {
            for (int i = 0; i < freqs.size(); i++) {
                GenericSelectView view = new GenericSelectView();
                initView(view, freqs.get(i), voltages.get(i));
                mVoltages.add(view);
            }
        }
        items.addAll(mVoltages);
    }

    private void addSuezVoltageItems(List<RecyclerViewItem> items, final int cpu,
                                     String cluster) {
        CardView card = new CardView();
        card.setTitle(cluster + " OPP voltage");
        List<SuezDVFS.OppVoltage> voltages = SuezDVFS.getOppVoltages(cpu);
        Collections.sort(voltages, new Comparator<SuezDVFS.OppVoltage>() {
            @Override
            public int compare(SuezDVFS.OppVoltage left, SuezDVFS.OppVoltage right) {
                return right.getFrequency() - left.getFrequency();
            }
        });

        for (final SuezDVFS.OppVoltage voltage : voltages) {
            SelectView view = new SelectView();
            List<String> choices = SuezDVFS.getVoltageChoices(voltage);
            view.setTitle(cluster + " " + (voltage.getFrequency() / 1000)
                    + getString(R.string.mhz));
            view.setSummary(suezVoltageSummary(voltage, voltage.getAssignedMillivolts()));
            view.setItems(choices);
            /* Match the item's raw value so SelectView can reopen at it. */
            view.setItem(String.valueOf(voltage.getAssignedMillivolts()));
            view.setOnItemSelected((selectView, position, item) -> {
                int millivolts = Utils.strToInt(item);
                selectView.setSummary(suezVoltageSummary(voltage, millivolts));
                SuezDVFS.setOppVoltage(cpu, voltage, millivolts,
                        ApplyOnBootFragment.CPU_VOLTAGE, getActivity());
            });
            mSuezVoltageViews.add(new SuezVoltageView(view, voltage));
            card.addItem(view);
        }
        items.add(card);
    }

    private void resetSuezVoltages(int cpu) {
        for (SuezDVFS.OppVoltage voltage : SuezDVFS.getOppVoltages(cpu)) {
            SuezDVFS.resetOppVoltage(cpu, voltage, ApplyOnBootFragment.CPU_VOLTAGE,
                    getActivity());
        }
    }

    private void addSuezLiveRailReadback(List<RecyclerViewItem> items) {
        List<SuezDVFS.OppVoltage> little = SuezDVFS.getOppVoltages(0);
        List<SuezDVFS.OppVoltage> big = SuezDVFS.getOppVoltages(2);
        if (little.isEmpty() || big.isEmpty()) return;

        SuezDVFS.OppVoltage littleLive = little.get(0);
        SuezDVFS.OppVoltage bigLive = big.get(0);
        DescriptionView rails = new DescriptionView();
        rails.setTitle("Live rail readback");
        rails.setSummary("LITTLE VPROC " + littleLive.getLiveVprocMillivolts()
                + getString(R.string.mv) + " · big VPROC "
                + bigLive.getLiveVprocMillivolts() + getString(R.string.mv)
                + " · big VSRAM " + bigLive.getLiveVsramMillivolts()
                + getString(R.string.mv));
        rails.setFullSpan(true);
        items.add(rails);
    }

    private void resetVisibleSuezVoltageViews() {
        for (SuezVoltageView voltageView : mSuezVoltageViews) {
            int stock = voltageView.mVoltage.getStockMillivolts();
            voltageView.mView.setItem(String.valueOf(stock));
            voltageView.mView.setSummary(suezVoltageSummary(voltageView.mVoltage, stock));
        }
    }

    private String suezVoltageSummary(SuezDVFS.OppVoltage voltage, int assignedMillivolts) {
        String summary = "Stock " + voltage.getStockMillivolts() + getString(R.string.mv);
        if (assignedMillivolts != voltage.getStockMillivolts()) {
            summary += " · selected " + assignedMillivolts + getString(R.string.mv);
        }
        summary += " · inactive OPPs apply on their next transition";
        return summary;
    }

    private void reload() {
        List<String> freqs = mVoltage.getFreqs();
        List<String> voltages = mVoltage.getVoltages();
        if (freqs != null && voltages != null) {
            for (int i = 0; i < mVoltages.size(); i++) {
                initView(mVoltages.get(i), freqs.get(i), voltages.get(i));
            }
        }
    }

    private void initView(GenericSelectView view, final String freq, String voltage) {
        String freqText = mVoltage.isVddVoltage() ? String.valueOf(Utils.strToInt(freq) / 1000) : freq;
        view.setTitle(freqText + getString(R.string.mhz));
        view.setSummary(voltage + getString(R.string.mv));
        view.setValue("");
        view.setValueRaw(voltage);
        view.setInputType(InputType.TYPE_CLASS_NUMBER);
        view.setOnGenericValueListener((genericSelectView, value) -> {
            mVoltage.setVoltage(freq, value, getActivity());
            getHandler().postDelayed(this::reload, 200);
        });
    }

    public static class GlobalOffsetFragment extends BaseFragment {

        public static GlobalOffsetFragment newInstance(CPUVoltageFragment cpuVoltageFragment) {
            GlobalOffsetFragment fragment = new GlobalOffsetFragment();
            fragment.mCPUVoltageFragment = cpuVoltageFragment;
            return fragment;
        }

        private CPUVoltageFragment mCPUVoltageFragment;
        private int mGlobaloffset;

        @Nullable
        @Override
        public View onCreateView(@NonNull LayoutInflater inflater,
                                 @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
            View rootView = inflater.inflate(R.layout.fragment_global_offset, container, false);
            final TextView offset = rootView.findViewById(R.id.offset);
            offset.setText(Utils.strFormat("%d" + getString(R.string.mv), mGlobaloffset));
            rootView.findViewById(R.id.button_minus).setOnClickListener(v -> {
                mGlobaloffset -= 5;
                offset.setText(Utils.strFormat("%d" + getString(R.string.mv), mGlobaloffset));
                Voltage.getInstance().setGlobalOffset(-5, getActivity());
                if (mCPUVoltageFragment != null) {
                    mCPUVoltageFragment.getHandler().postDelayed(() ->
                            mCPUVoltageFragment.reload(), 200);
                }
            });
            rootView.findViewById(R.id.button_plus).setOnClickListener(v -> {
                mGlobaloffset += 5;
                offset.setText(Utils.strFormat("%d" + getString(R.string.mv), mGlobaloffset));
                Voltage.getInstance().setGlobalOffset(5, getActivity());
                if (mCPUVoltageFragment != null) {
                    mCPUVoltageFragment.getHandler().postDelayed(()
                            -> mCPUVoltageFragment.reload(), 200);
                }
            });
            return rootView;
        }
    }
}
