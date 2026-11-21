package com.grarak.kerneladiutor.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.text.TextUtils;

import com.grarak.kerneladiutor.utils.NotificationId;
import com.grarak.kerneladiutor.utils.Utils;
import com.grarak.kerneladiutor.utils.kernel.vm.ZRAM;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public class ZramTrainingService extends Service {

    public static final int MAX_WORKLOADS = 4;
    public static final String ANDROID_SYSTEM = "system_server";
    private static final String ASSET = "arm64/zram_trainer";
    private static final String CHANNEL = "zram_training";
    private static final String STOP = "stop_zram_training";
    private static final String BUILD = "build_zram_dictionary";
    private static final String DISCARD = "discard_zram_samples";
    private static final String PACKAGES = "packages";
    private static final String AUTOMATIC = "automatic";
    private static final String LAST_RESULT = "zram_training_result";
    private static final String DICTIONARY_REPORT = "zram_dictionary_report.";
    private static final int PAGE_BYTES = 4096;
    private static final int MIN_PAGES = 128;
    private static final int BATCH_PAGES = 128;
    private static final int ROUND_PAGES = 32;
    private static final int WORKLOAD_PAGES = 512;
    private static final int MAX_PAGES = MAX_WORKLOADS * WORKLOAD_PAGES;
    private static final int ROUND_INTERVAL_MS = 30_000;
    private static final Pattern PACKAGE_NAME =
            Pattern.compile("[A-Za-z][A-Za-z0-9_]*(?:\\.[A-Za-z][A-Za-z0-9_]*)+");

    private static volatile String sStatus;
    private static volatile boolean sRunning;
    private static volatile boolean sTraining;
    private static volatile boolean sReady;
    private final Object mWake = new Object();
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private volatile boolean mStop;
    private volatile boolean mCancelled;
    private ArrayList<String> mPackages;
    private boolean mAutomatic;
    private boolean mBuild;
    private boolean mDiscard;
    private volatile Process mProcess;

    public static boolean available(Context context) {
        if (context == null || Build.VERSION.SDK_INT < 21
                || !Arrays.asList(Build.SUPPORTED_64_BIT_ABIS).contains("arm64-v8a")) return false;
        try (InputStream asset = context.getAssets().open(ASSET)) {
            return asset.read() >= 0;
        } catch (IOException e) {
            return false;
        }
    }

    public static boolean validPackage(String name) {
        return PACKAGE_NAME.matcher(name).matches();
    }

    public static boolean isRunning() {
        return sRunning;
    }

    public static boolean isTraining() {
        return sTraining;
    }

    public static boolean isReady() {
        return sReady;
    }

    public static String status(Context context) {
        return sStatus == null ? PreferenceManager.getDefaultSharedPreferences(context)
                .getString(LAST_RESULT, "Tap to choose apps and start sampling.") : sStatus;
    }

    public static String dictionaryReport(Context context, String filename) {
        return PreferenceManager.getDefaultSharedPreferences(context)
                .getString(DICTIONARY_REPORT + filename, "");
    }

    public static void start(Context context, ArrayList<String> packages, boolean automatic) {
        Utils.startService(context, new Intent(context, ZramTrainingService.class)
                .putStringArrayListExtra(PACKAGES, packages)
                .putExtra(AUTOMATIC, automatic));
    }

    public static void stop(Context context) {
        sendAction(context, STOP);
    }

    public static void build(Context context) {
        sendAction(context, BUILD);
    }

    public static void discard(Context context) {
        sendAction(context, DISCARD);
    }

    private static void sendAction(Context context, String action) {
        context.startService(new Intent(context, ZramTrainingService.class).setAction(action));
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = intent.getAction();
        if (STOP.equals(action) || BUILD.equals(action) || DISCARD.equals(action)) {
            if (sRunning && !sTraining) {
                synchronized (mWake) {
                    if (STOP.equals(action)) mStop = true;
                    if (sReady && BUILD.equals(action)) mBuild = true;
                    if (sReady && DISCARD.equals(action)) mDiscard = true;
                    mWake.notifyAll();
                }
            } else if (!sRunning) {
                stopSelf();
            }
            return START_NOT_STICKY;
        }
        if (sRunning) return START_NOT_STICKY;
        ArrayList<String> packages = intent.getStringArrayListExtra(PACKAGES);
        boolean automatic = intent.getBooleanExtra(AUTOMATIC, false);
        mPackages = packages;
        mAutomatic = automatic;
        mStop = false;
        mBuild = false;
        mDiscard = false;
        sRunning = true;
        sStatus = "Preparing sampler…";
        if (Build.VERSION.SDK_INT >= 26) {
            manager().createNotificationChannel(new NotificationChannel(
                    CHANNEL, "ZRAM dictionary training", NotificationManager.IMPORTANCE_LOW));
        }
        startForeground(NotificationId.ZRAM_TRAINING, notification(true));
        new Thread(() -> runSession(packages, automatic), "ZRAM dictionary trainer").start();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        mCancelled = true;
        synchronized (mWake) {
            mWake.notifyAll();
        }
        Process process = mProcess;
        if (process != null) process.destroy();
        super.onDestroy();
    }

    private NotificationManager manager() {
        return (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
    }

    private Notification notification(boolean running) {
        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("ZRAM dictionary training")
                .setContentText(sStatus)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(sStatus))
                .setOnlyAlertOnce(true)
                .setOngoing(running);
        if (running && !sTraining) {
            if (sReady) {
                builder.addAction(0, "Build when idle", notificationAction(BUILD));
                builder.addAction(0, "Discard samples", notificationAction(DISCARD));
            } else {
                builder.addAction(0, "Stop sampling", notificationAction(STOP));
            }
        } else if (!running && mPackages != null && !mPackages.isEmpty()) {
            Intent start = new Intent(this, ZramTrainingService.class)
                    .putStringArrayListExtra(PACKAGES, mPackages)
                    .putExtra(AUTOMATIC, mAutomatic);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE;
            PendingIntent retry = Build.VERSION.SDK_INT >= 26
                    ? PendingIntent.getForegroundService(this, 0, start, flags)
                    : PendingIntent.getService(this, 0, start, flags);
            builder.addAction(0, mAutomatic ? "Start Auto sampling" : "Sample same apps", retry);
        }
        return builder.build();
    }

    private PendingIntent notificationAction(String action) {
        return PendingIntent.getService(this, 0,
                new Intent(this, ZramTrainingService.class).setAction(action),
                PendingIntent.FLAG_IMMUTABLE);
    }

    private void progress(String message) {
        sStatus = message;
        if (!mCancelled) manager().notify(NotificationId.ZRAM_TRAINING, notification(true));
    }

    private void runSession(ArrayList<String> packages, boolean automatic) {
        File helper = null;
        String result;
        try {
            if (packages == null || packages.isEmpty()
                    || (!automatic && packages.size() > MAX_WORKLOADS)
                    || new HashSet<>(packages).size() != packages.size()) {
                throw new IOException("Choose one to four distinct apps.");
            }
            for (String name : packages) {
                if ((!ANDROID_SYSTEM.equals(name) && !validPackage(name))
                        || name.equals(getPackageName()))
                    throw new IOException("Invalid app selection.");
            }
            helper = File.createTempFile("zram-trainer-", "", getCacheDir());
            try (InputStream asset = getAssets().open(ASSET);
                 OutputStream output = new FileOutputStream(helper)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = asset.read(buffer)) != -1) output.write(buffer, 0, read);
            }
            if (!helper.setExecutable(true, true))
                throw new IOException("Could not make the trainer executable.");
            requireSuccess(runRoot("test \"$(id -u)\" = 0", null, 0),
                    "Kernel Adiutor needs root access.");
            result = collectAndTrain(helper, packages, automatic);
        } catch (IOException e) {
            result = "Training stopped. " + e.getMessage();
        } catch (InterruptedException | NoSuchAlgorithmException e) {
            result = "Training interrupted.";
        } finally {
            if (helper != null) helper.delete();
        }
        final String message = result;
        mMain.post(() -> {
            sTraining = false;
            sReady = false;
            sRunning = false;
            sStatus = message;
            if (!mCancelled) {
                PreferenceManager.getDefaultSharedPreferences(this).edit()
                        .putString(LAST_RESULT, message).apply();
                stopForeground(true);
                manager().notify(NotificationId.ZRAM_TRAINING, notification(false));
                stopSelf();
            }
        });
    }

    private String collectAndTrain(File helper, List<String> packages, boolean automatic)
            throws IOException, InterruptedException, NoSuchAlgorithmException {
        List<byte[]> pages = new ArrayList<>();
        Set<ByteBuffer> seen = new HashSet<>();
        int[] counts = new int[packages.size()];
        List<String> labels = workloadLabels(packages);
        int target = Math.min(MAX_PAGES, packages.size() * WORKLOAD_PAGES);
        int nextWorkload = 0;
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        Random random = new Random();
        while (!mStop && !mCancelled && pages.size() < target) {
            boolean lowMemory = false;
            boolean captured = false;
            Set<String> running = automatic ? runningWorkloads() : null;
            int attempted = 0;
            for (int visited = 0; visited < packages.size() && attempted < MAX_WORKLOADS
                    && pages.size() < target && !mStop && !mCancelled; visited++) {
                int i = nextWorkload;
                nextWorkload = (nextWorkload + 1) % packages.size();
                String name = packages.get(i);
                int remaining = Math.min(WORKLOAD_PAGES - counts[i], target - pages.size());
                if (remaining == 0 || (running != null && !running.contains(name))) continue;
                attempted++;
                String command = "pid=$(pidof -s '" + name + "') || exit 3; "
                        + "exec timeout 12 " + quote(helper.getPath()) + " capture \"$pid\" '"
                        + name + "' " + BATCH_PAGES + " " + random.nextInt(Integer.MAX_VALUE)
                        + " 2>/dev/null";
                Result sample = runRoot(command, null, BATCH_PAGES * PAGE_BYTES);
                if (sample.code == 2) {
                    lowMemory = true;
                    break;
                }
                if (sample.code == 3 || sample.code == 124) continue;
                if (automatic && sample.code != 0) continue;
                requireSuccess(sample, "Could not read " + name + " through KernelSU.");
                if (sample.bytes.length % PAGE_BYTES != 0)
                    throw new IOException("The sampler returned a partial page.");
                counts[i] += addPages(sample.bytes, Math.min(ROUND_PAGES, remaining),
                        pages, seen, digest);
                captured |= sample.bytes.length > 0;
            }
            if (!mStop && !mCancelled && pages.size() < target) {
                String state = lowMemory ? "Paused for low memory."
                        : automatic ? "Auto sampling running workloads."
                        : captured ? "Sampling selected apps." : "Waiting for a selected app to run.";
                progress(state + " " + pages.size() + " / " + target + " unique pages.");
                long next = SystemClock.elapsedRealtime() + ROUND_INTERVAL_MS;
                synchronized (mWake) {
                    long remaining;
                    while (!mStop && !mCancelled
                            && (remaining = next - SystemClock.elapsedRealtime()) > 0) {
                        mWake.wait(remaining);
                    }
                }
            }
        }
        if (mCancelled) throw new InterruptedException();
        if (pages.size() < MIN_PAGES)
            return "Collected " + pages.size() + " unique pages. Training needs at least "
                    + MIN_PAGES + ".";
        Collections.shuffle(pages, random);
        Result dictionary;
        List<Integer> sources = topSources(counts);
        List<String> sourceDetails = new ArrayList<>();
        List<String> filenameParts = new ArrayList<>();
        for (int index : sources) {
            sourceDetails.add(labels.get(index) + " (" + counts[index] + " pages)");
            filenameParts.add(shortAppName(labels.get(index), packages.get(index)));
        }
        String source = "Top contributors: " + TextUtils.join(", ", sourceDetails) + ".";
        String ready = pages.size() + " pages ready. Build when the tablet is idle. " + source;
        while (true) {
            synchronized (mWake) {
                sReady = true;
                sTraining = false;
                progress(ready);
                while (!mBuild && !mDiscard && !mCancelled) mWake.wait();
                if (mCancelled) throw new InterruptedException();
                if (mDiscard) return "Training samples discarded.";
                mBuild = false;
                sReady = false;
                sTraining = true;
            }
            progress("Building a dictionary from " + pages.size() + " pages…");
            dictionary = runRoot("exec timeout 120 nice -n 10 "
                    + quote(helper.getPath()) + " train 2>/dev/null", pages, 16384);
            if (dictionary.code != 2) break;
            ready = "Tablet is busy or low on memory. " + pages.size() + " pages kept for retry.";
        }
        requireSuccess(dictionary, "The dictionary backend could not build a candidate.");
        if (dictionary.bytes.length < 8)
            throw new IOException("The trainer returned an empty dictionary.");
        String name = "zram-" + TextUtils.join("-", filenameParts) + "-"
                + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
                .format(new Date()) + "-" + UUID.randomUUID().toString().substring(0, 8) + ".dict";
        saveDictionary(name, dictionary.bytes);
        String report = pages.size() + " pages · " + dictionary.bytes.length + " B. " + source;
        PreferenceManager.getDefaultSharedPreferences(this).edit()
                .putString(DICTIONARY_REPORT + name, report).apply();
        return "Saved " + name + "\n" + report;
    }

    private Set<String> runningWorkloads() throws IOException, InterruptedException {
        Result result = runRoot("exec ps -A -o NAME", null, 128 * 1024);
        requireSuccess(result, "Could not list running workloads.");
        String names = new String(result.bytes, StandardCharsets.UTF_8);
        return new HashSet<>(Arrays.asList(names.trim().split("\\s+")));
    }

    private List<String> workloadLabels(List<String> packages) {
        PackageManager manager = getPackageManager();
        List<String> labels = new ArrayList<>();
        for (String name : packages) {
            if (ANDROID_SYSTEM.equals(name)) {
                labels.add("Android System");
                continue;
            }
            try {
                labels.add(manager.getApplicationInfo(name, 0).loadLabel(manager).toString());
            } catch (PackageManager.NameNotFoundException e) {
                labels.add(name);
            }
        }
        return labels;
    }

    private List<Integer> topSources(int[] counts) {
        List<Integer> sources = new ArrayList<>();
        for (int i = 0; i < counts.length; i++) {
            if (counts[i] > 0) sources.add(i);
        }
        Collections.sort(sources, (left, right) -> Integer.compare(counts[right], counts[left]));
        return sources.subList(0, Math.min(2, sources.size()));
    }

    private String shortAppName(String label, String packageName) {
        String name = Normalizer.normalize(label, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        if (name.isEmpty()) {
            name = packageName.substring(packageName.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        }
        return name.substring(0, Math.min(14, name.length())).replaceAll("-$", "");
    }

    private int addPages(byte[] batch, int limit, List<byte[]> pages, Set<ByteBuffer> seen,
                         MessageDigest digest) {
        int added = 0;
        for (int start = 0; start < batch.length && added < limit; start += PAGE_BYTES) {
            int end = start + PAGE_BYTES;
            int i = start + 8;
            while (i < end && batch[i] == batch[i - 8]) i++;
            if (i == end) continue;
            digest.update(batch, start, PAGE_BYTES);
            if (seen.add(ByteBuffer.wrap(digest.digest()))) {
                pages.add(Arrays.copyOfRange(batch, start, end));
                added++;
            }
        }
        return added;
    }

    private void saveDictionary(String name, byte[] bytes) throws IOException, InterruptedException {
        String script = "dir='" + ZRAM.DICTIONARY_DIRECTORY + "'; "
                + "mkdir -p \"$dir\" 2>/dev/null || dir='" + ZRAM.DICTIONARY_BACKING_DIRECTORY + "'; "
                + "mkdir -p \"$dir\" || exit 1; umask 077; "
                + "tmp=\"$dir/." + name + ".tmp\"; dst=\"$dir/" + name + "\"; "
                + "trap 'rm -f \"$tmp\"' EXIT; "
                + "cat > \"$tmp\" && [ \"$(stat -c %s \"$tmp\")\" = " + bytes.length
                + " ] && [ ! -e \"$dst\" ] && mv \"$tmp\" \"$dst\"";
        requireSuccess(runRoot(script, Collections.singletonList(bytes), 32),
                "Could not save the dictionary in Download/KernelAdiutor.");
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private Result runRoot(String command, List<byte[]> input, int maxOutput)
            throws IOException, InterruptedException {
        if (mCancelled) throw new InterruptedException();
        Process process = new ProcessBuilder("su", "-c", command).start();
        mProcess = process;
        try {
            if (mCancelled) throw new InterruptedException();
            try (OutputStream output = process.getOutputStream()) {
                if (input != null) {
                    for (byte[] chunk : input) output.write(chunk);
                }
            } catch (IOException e) {
                // A helper that fails before reading stdin reports its exit status below.
                if (process.waitFor() == 0) throw e;
            }
            try (InputStream output = process.getInputStream()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                byte[] buffer = new byte[8192];
                int count;
                while ((count = output.read(buffer)) != -1) {
                    if (bytes.size() + count > maxOutput)
                        throw new IOException("Unexpected output from the dictionary helper.");
                    bytes.write(buffer, 0, count);
                }
                return new Result(process.waitFor(), bytes.toByteArray());
            }
        } finally {
            process.destroy();
            mProcess = null;
        }
    }

    private static void requireSuccess(Result result, String message) throws IOException {
        if (result.code != 0) throw new IOException(message);
    }

    private static class Result {
        final int code;
        final byte[] bytes;

        Result(int code, byte[] bytes) {
            this.code = code;
            this.bytes = bytes;
        }
    }
}
