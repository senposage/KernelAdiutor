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
package com.grarak.kerneladiutor.database;

import android.content.Context;

import com.grarak.kerneladiutor.utils.Utils;
import com.grarak.kerneladiutor.utils.kernel.cpu.T227UDVFS;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;

/**
 * Created by willi on 15.04.15.
 */
public class Settings extends Provider {

    private static final String SETTINGS_FILE = "settings.json";

    /**
     * Read the JSON file from cache
     *
     * @param context needed to get the cache directory
     */
    public Settings(Context context) {
        super(new File(context.getFilesDir(), SETTINGS_FILE).getPath(), 1);

        if (Utils.existFile(context.getFilesDir() + "/commands.json", false)) {
            new File(context.getFilesDir() + "/commands.json").delete();
        }
    }

    @Override
    public DBJsonItem getItem(JSONObject item) {
        return new SettingsItem(item);
    }

    public void putSetting(String category, String setting, String id) {
        try {
            JSONObject items = new JSONObject();
            items.put("category", category);
            items.put("setting", setting);
            items.put("id", id);
            putItem(items);
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    public List<SettingsItem> getAllSettings() {
        List<SettingsItem> items = new ArrayList<>();
        for (DBJsonItem jsonItem : getAllItems()) {
            items.add((SettingsItem) jsonItem);
        }
        return items;
    }

    /** Runs on the settings writer's executor. Keep the original file on write failure. */
    public static int removeLegacyT227UCpuOnlineSettings(Context context, String cpuCategory)
            throws IOException, JSONException {
        File settings = new File(context.getFilesDir(), SETTINGS_FILE);
        String text = Utils.readFile(settings.getPath(), false);
        if (text == null) return 0;

        JSONObject database = new JSONObject(text);
        if (database.optInt("version") != 1) return 0;

        JSONArray entries = database.getJSONArray("database");
        JSONArray kept = new JSONArray();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject item = entries.optJSONObject(i);
            if (item != null && cpuCategory.equals(item.optString("category"))
                    && T227UDVFS.isLegacyOnlineCommand(item.optString("setting"))) {
                continue;
            }
            kept.put(entries.get(i));
        }
        int removed = entries.length() - kept.length();
        if (removed == 0) return 0;

        database.put("database", kept);
        File temporary = File.createTempFile("settings-t227u-", ".json", context.getFilesDir());
        try {
            try (FileOutputStream stream = new FileOutputStream(temporary)) {
                stream.write(database.toString().getBytes(Charset.forName("UTF-8")));
                stream.getFD().sync();
            }
            if (!temporary.renameTo(settings)) {
                throw new IOException("Could not replace " + SETTINGS_FILE);
            }
        } finally {
            temporary.delete();
        }
        return removed;
    }

    public static class SettingsItem extends DBJsonItem {

        private SettingsItem(JSONObject object) {
            super(object);
        }

        public String getSetting() {
            return getString("setting");
        }

        public String getCategory() {
            return getString("category");
        }

        public String getId() {
            return getString("id");
        }

    }

}
