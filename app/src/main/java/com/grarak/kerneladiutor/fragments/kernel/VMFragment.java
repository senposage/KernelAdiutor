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

import android.text.InputType;

import com.grarak.kerneladiutor.R;
import com.grarak.kerneladiutor.fragments.ApplyOnBootFragment;
import com.grarak.kerneladiutor.fragments.recyclerview.RecyclerViewFragment;
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
import java.util.List;

/**
 * Created by willi on 29.06.16.
 */
public class VMFragment extends RecyclerViewFragment {

    private List<GenericSelectView> mVMs = new ArrayList<>();
    private List<Integer> mVMPositions = new ArrayList<>();
    private int mPendingZramSize;
    private boolean mZramResizeInProgress;

    @Override
    protected void init() {
        super.init();

        addViewPagerFragment(ApplyOnBootFragment.newInstance(this));
    }

    @Override
    protected void addItems(List<RecyclerViewItem> items) {
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
            dictionary.setSummary("Copy zram-lz4.dict to Download/KernelAdiutor and enable "
                    + "Virtual Memory Apply on Boot. It loads before ZRAM starts.");
            items.add(dictionary);
        }

        DescriptionView apply = new DescriptionView();
        apply.setTitle("Apply ZRAM size");
        apply.setSummary("Applies the selected size once after swap is recreated.");
        apply.setOnItemClickListener(item ->
                applyZramResize(zram, readback, apply));
        items.add(apply);
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
