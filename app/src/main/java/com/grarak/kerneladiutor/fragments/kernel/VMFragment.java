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

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.preference.PreferenceManager;
import android.support.v7.app.AlertDialog;
import android.text.InputType;

import com.grarak.kerneladiutor.R;
import com.grarak.kerneladiutor.fragments.ApplyOnBootFragment;
import com.grarak.kerneladiutor.fragments.recyclerview.RecyclerViewFragment;
import com.grarak.kerneladiutor.services.ZramTrainingService;
import com.grarak.kerneladiutor.utils.Utils;
import com.grarak.kerneladiutor.utils.kernel.vm.VM;
import com.grarak.kerneladiutor.utils.kernel.vm.ZRAM;
import com.grarak.kerneladiutor.utils.kernel.vm.ZSwap;
import com.grarak.kerneladiutor.views.recyclerview.CardView;
import com.grarak.kerneladiutor.views.recyclerview.DescriptionView;
import com.grarak.kerneladiutor.views.recyclerview.GenericSelectView;
import com.grarak.kerneladiutor.views.recyclerview.RecyclerViewItem;
import com.grarak.kerneladiutor.views.recyclerview.SeekBarView;
import com.grarak.kerneladiutor.views.recyclerview.SwitchView;
import com.grarak.kerneladiutor.views.recyclerview.TitleView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Created by willi on 29.06.16.
 */
public class VMFragment extends RecyclerViewFragment {

    private static final String AUTO_EXCLUSIONS = "zram_training_auto_exclusions";
    private static final String MANUAL_WORKLOADS_TITLE = "Choose up to four processes";
    private static final String AUTO_WORKLOADS_TITLE = "Auto · check processes to exclude";

    private List<GenericSelectView> mVMs = new ArrayList<>();
    private List<Integer> mVMPositions = new ArrayList<>();
    private int mPendingZramSize;
    private boolean mZramResizeInProgress;
    private DescriptionView mTrainer;

    @Override
    protected void init() {
        super.init();

        addViewPagerFragment(ApplyOnBootFragment.newInstance(this));
    }

    @Override
    protected void addItems(List<RecyclerViewItem> items) {
        mTrainer = null;
        mVMs.clear();
        mVMPositions.clear();
        for (int i = 0; i < VM.size(); i++) {
            if (VM.exists(i)) {
                GenericSelectView vm = new GenericSelectView();
                vm.setSummary(VM.getName(i));
                vm.setValue(VM.getValue(i));
                vm.setValueRaw(vm.getValue());
                vm.setInputType(InputType.TYPE_CLASS_NUMBER);

                final int position = i;
                vm.setOnGenericValueListener((genericSelectView, value) -> {
                    VM.setValue(value, position, getActivity());
                    genericSelectView.setValue(value);
                    refreshVMs();
                });

                items.add(vm);
                mVMs.add(vm);
                mVMPositions.add(i);
            }
        }

        if (ZRAM.supported()) {
            zramInit(items);
        }
        zswapInit(items);
    }

    private void zramInit(List<RecyclerViewItem> items) {
        TitleView zramTitle = new TitleView();
        zramTitle.setText(getString(R.string.zram));
        items.add(zramTitle);

        DescriptionView readback = new DescriptionView();
        readback.setTitle("Active ZRAM readback");
        readback.setSummary(zramReadback());

        SeekBarView zram = new SeekBarView();
        zram.setTitle(getString(R.string.disksize));
        zram.setSummary(getString(R.string.disksize_summary));
        zram.setUnit(getString(R.string.mb));
        zram.setMax(2048);
        zram.setOffset(10);
        zram.setProgress(ZRAM.getDisksize() / 10);
        zram.setOnSeekBarListener(new SeekBarView.OnSeekBarListener() {
            @Override
            public void onStop(SeekBarView seekBarView, int position, String value) {
                mPendingZramSize = position * 10;
            }

            @Override
            public void onMove(SeekBarView seekBarView, int position, String value) {
                mPendingZramSize = position * 10;
            }
        });

        mPendingZramSize = ZRAM.getDisksize();
        items.add(zram);
        items.add(readback);

        if (ZRAM.supportsDictionary()) {
            DescriptionView dictionary = new DescriptionView();
            dictionary.setTitle("LZ4 dictionary");
            showDictionarySelection(dictionary);
            dictionary.setOnItemClickListener(item -> pickDictionary(dictionary));
            items.add(dictionary);
            if (ZramTrainingService.available(getActivity())) {
                mTrainer = new DescriptionView();
                mTrainer.setOnItemClickListener(item -> trainingAction());
                refresh();
                items.add(mTrainer);
            }
        }

        DescriptionView apply = new DescriptionView();
        apply.setTitle("Apply ZRAM size");
        apply.setSummary("Applies the selected size once after swap is recreated.");
        apply.setOnItemClickListener(item ->
                applyZramResize(zram, readback, apply));
        items.add(apply);
    }

    @Override
    protected void refresh() {
        Context context = getActivity();
        if (mTrainer != null && context != null) {
            CharSequence title = ZramTrainingService.isTraining() ? "Building an LZ4 dictionary"
                    : ZramTrainingService.isReady() ? "Build an LZ4 dictionary"
                    : ZramTrainingService.isRunning() ? "Stop sampling"
                    : ZramTrainingService.canResume(context) ? "Resume LZ4 training"
                    : "Train an LZ4 dictionary";
            if (!title.equals(mTrainer.getTitle())) mTrainer.setTitle(title);
            String status = ZramTrainingService.status(context);
            if (!status.equals(mTrainer.getSummary())) mTrainer.setSummary(status);
        }
    }

    private void trainingAction() {
        Context context = getActivity();
        if (context == null || ZramTrainingService.isTraining()) return;
        if (ZramTrainingService.isReady()) {
            new AlertDialog.Builder(context)
                    .setTitle("Build dictionary")
                    .setMessage("Close heavy workloads before building. The candidate is saved "
                            + "in Download/KernelAdiutor.")
                    .setPositiveButton("Build", (dialog, which) -> ZramTrainingService.build(context))
                    .setNeutralButton("Discard samples",
                            (dialog, which) -> ZramTrainingService.discard(context))
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
        } else if (ZramTrainingService.isRunning()) {
            ZramTrainingService.stop(context);
        } else if (ZramTrainingService.canResume(context)) {
            ZramTrainingService.resume(context);
        } else {
            chooseWorkloads(context);
        }
    }

    private void chooseWorkloads(Context context) {
        PackageManager manager = context.getPackageManager();
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> apps = new ArrayList<>(manager.queryIntentActivities(launcher, 0));
        Collections.sort(apps, new ResolveInfo.DisplayNameComparator(manager));
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(ZramTrainingService.ANDROID_SYSTEM, "Android System\nsystem_server");
        for (ResolveInfo app : apps) {
            String name = app.activityInfo.packageName;
            if (!name.equals(context.getPackageName()) && ZramTrainingService.validPackage(name)) {
                labels.put(name, app.loadLabel(manager) + "\n" + name);
            }
        }
        List<String> packages = new ArrayList<>(labels.keySet());
        List<String> choices = new ArrayList<>(labels.values());
        choices.add(0, "Auto\nSample running workloads automatically");
        boolean[] selected = new boolean[choices.size()];
        SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(context);
        Set<String> savedExclusions = preferences.getStringSet(AUTO_EXCLUSIONS, Collections.emptySet());
        AlertDialog dialog = new AlertDialog.Builder(context)
                .setTitle(MANUAL_WORKLOADS_TITLE)
                .setMultiChoiceItems(choices.toArray(new String[0]), selected,
                        (picker, which, checked) -> {
                            selected[which] = checked;
                            if (which != 0) return;
                            AlertDialog listDialog = (AlertDialog) picker;
                            listDialog.setTitle(checked ? AUTO_WORKLOADS_TITLE : MANUAL_WORKLOADS_TITLE);
                            for (int i = 0; i < packages.size(); i++) {
                                int row = i + 1;
                                selected[row] = checked && savedExclusions.contains(packages.get(i));
                                listDialog.getListView().setItemChecked(row, selected[row]);
                            }
                        })
                .setPositiveButton("Continue", null)
                .setNegativeButton(android.R.string.cancel, null)
                .create();
        dialog.setOnShowListener(ignored ->
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                    boolean automatic = selected[0];
                    ArrayList<String> chosen = new ArrayList<>();
                    Set<String> exclusions = new HashSet<>();
                    for (int i = 0; i < packages.size(); i++) {
                        String name = packages.get(i);
                        if (automatic && selected[i + 1]) exclusions.add(name);
                        if (selected[i + 1] != automatic) chosen.add(name);
                    }
                    if (chosen.isEmpty() || (!automatic
                            && chosen.size() > ZramTrainingService.MAX_WORKLOADS)) {
                        Utils.toast(automatic ? "Leave at least one process available."
                                : "Choose one to four processes.", context);
                        return;
                    }
                    dialog.dismiss();
                    String scope = automatic ? "KA samples running processes in the list, "
                            + "except for your exclusions. "
                            : "KA samples the selected processes while they are running. ";
                    new AlertDialog.Builder(context)
                            .setTitle("Start workload sampling")
                            .setMessage(scope + "A dictionary can contain fragments of their memory. "
                                    + "Keep personal dictionaries local and stop sampling when done.")
                            .setPositiveButton("Start sampling", (confirm, which) -> {
                                if (automatic) preferences.edit()
                                        .putStringSet(AUTO_EXCLUSIONS, exclusions).apply();
                                ZramTrainingService.start(context, chosen, automatic);
                            })
                            .setNegativeButton(android.R.string.cancel, null)
                            .show();
                }));
        dialog.show();
    }

    private void showDictionarySelection(DescriptionView dictionary) {
        Context context = getActivity();
        if (context == null) return;
        String name = ZRAM.selectedDictionary(context);
        String report = name.isEmpty() ? "" : ZramTrainingService.dictionaryReport(context, name);
        dictionary.setSummary((name.isEmpty() ? "Plain LZ4" : name)
                + " selected for next ZRAM setup. Tap to choose."
                + (report.isEmpty() ? "" : "\n" + report));
    }

    private void pickDictionary(DescriptionView dictionary) {
        Context context = getActivity();
        if (context == null) return;
        List<String> names = ZRAM.listDictionaries();
        names.add(0, "");
        String[] labels = names.toArray(new String[0]);
        labels[0] = "Plain LZ4";
        new AlertDialog.Builder(context)
                .setTitle("LZ4 dictionaries in Download/KernelAdiutor")
                .setSingleChoiceItems(labels, names.indexOf(ZRAM.selectedDictionary(context)),
                        (dialog, which) -> {
                            ZRAM.selectDictionary(context, names.get(which));
                            showDictionarySelection(dictionary);
                            dialog.dismiss();
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private String zramReadback() {
        String algorithm = ZRAM.getCompressionAlgorithm();
        String streams = ZRAM.getMaxCompressionStreams();
        String dictionary = "";
        if (ZRAM.supportsDictionary()) {
            int size = ZRAM.getDictionarySize();
            dictionary = size > 0 ? " · dictionary " + size + " B" : " · plain";
        }
        return ZRAM.getDisksize() + " MB · "
                + (algorithm == null ? "unknown compressor" : algorithm)
                + " · " + (streams == null ? "unknown" : streams) + " streams" + dictionary;
    }

    /** A resize tears down active swap, so never bind it to slider movement. */
    private void applyZramResize(final SeekBarView seekBarView,
                                 final DescriptionView readback,
                                 final DescriptionView apply) {
        if (mZramResizeInProgress) return;
        mZramResizeInProgress = true;
        seekBarView.setEnabled(false);
        readback.setSummary("Applying " + mPendingZramSize + " MB…");
        apply.setSummary("Resize in progress…");
        ZRAM.setDisksize(mPendingZramSize, getActivity());
        confirmZramResize(seekBarView, readback, apply, mPendingZramSize, 0);
    }

    private void confirmZramResize(final SeekBarView seekBarView,
                                   final DescriptionView readback,
                                   final DescriptionView apply,
                                   final int requestedSizeMb, final int attempt) {
        getHandler().postDelayed(() -> {
            int actualSizeMb = ZRAM.getDisksize();
            if (actualSizeMb == requestedSizeMb || attempt >= 20) {
                // Do not ever replace the selected slider value with the
                // transient zero observed while reset is in progress.
                if (actualSizeMb == requestedSizeMb) {
                    seekBarView.setProgress(actualSizeMb / 10);
                    readback.setSummary(zramReadback());
                    apply.setSummary("Applied " + actualSizeMb + " MB. Tap to apply a new selection.");
                } else {
                    readback.setSummary("Resize did not settle; current readback is "
                            + actualSizeMb + " MB.");
                    apply.setSummary("Tap to retry the selected " + requestedSizeMb + " MB.");
                }
                seekBarView.setEnabled(true);
                mZramResizeInProgress = false;
            } else {
                confirmZramResize(seekBarView, readback, apply, requestedSizeMb, attempt + 1);
            }
        }, 250);
    }

    private void zswapInit(List<RecyclerViewItem> items) {
        CardView zswapCard = new CardView();
        zswapCard.setTitle(getString(R.string.zswap));

        if (ZSwap.hasEnable()) {
            SwitchView zswap = new SwitchView();
            zswap.setTitle(getString(R.string.zswap));
            zswap.setSummary(getString(R.string.zswap_summary));
            zswap.setChecked(ZSwap.isEnabled());
            zswap.addOnSwitchListener((switchView, isChecked)
                    -> ZSwap.enable(isChecked, getActivity()));

            zswapCard.addItem(zswap);
        }

        if (ZSwap.hasMaxPoolPercent()) {
            SeekBarView maxPoolPercent = new SeekBarView();
            maxPoolPercent.setTitle(getString(R.string.memory_pool));
            maxPoolPercent.setSummary(getString(R.string.memory_pool_summary));
            maxPoolPercent.setUnit("%");
            maxPoolPercent.setMax(50);
            maxPoolPercent.setProgress(ZSwap.getMaxPoolPercent());
            maxPoolPercent.setOnSeekBarListener(new SeekBarView.OnSeekBarListener() {
                @Override
                public void onStop(SeekBarView seekBarView, int position, String value) {
                    ZSwap.setMaxPoolPercent(position, getActivity());
                }

                @Override
                public void onMove(SeekBarView seekBarView, int position, String value) {
                }
            });

            zswapCard.addItem(maxPoolPercent);
        }

        if (ZSwap.hasMaxCompressionRatio()) {
            SeekBarView maxCompressionRatio = new SeekBarView();
            maxCompressionRatio.setTitle(getString(R.string.maximum_compression_ratio));
            maxCompressionRatio.setSummary(getString(R.string.maximum_compression_ratio_summary));
            maxCompressionRatio.setUnit("%");
            maxCompressionRatio.setProgress(ZSwap.getMaxCompressionRatio());
            maxCompressionRatio.setOnSeekBarListener(new SeekBarView.OnSeekBarListener() {
                @Override
                public void onStop(SeekBarView seekBarView, int position, String value) {
                    ZSwap.setMaxCompressionRatio(position, getActivity());
                }

                @Override
                public void onMove(SeekBarView seekBarView, int position, String value) {
                }
            });

            zswapCard.addItem(maxCompressionRatio);
        }

        if (zswapCard.size() > 0) {
            items.add(zswapCard);
        }
    }

    private void refreshVMs() {
        getHandler().postDelayed(() -> {
            for (int i = 0; i < mVMs.size(); i++) {
                mVMs.get(i).setValue(VM.getValue(mVMPositions.get(i)));
                mVMs.get(i).setValueRaw(mVMs.get(i).getValue());
            }
        }, 250);
    }

}
