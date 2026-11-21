# T227U Kernel Adiutor dictionary trainer

In Virtual Memory, tap **Train an LZ4 dictionary** and select up to
four apps. KA takes small samples from resident writable anonymous
pages in their main processes about every 90 seconds. It skips
duplicate and repeated-fill pages and holds at most 512 usable pages
per selected app. Sampling stops at the quota or with **Stop sampling**.
At least 128 usable pages are needed to build.

Check **Auto** at the top of the workload picker to rotate through running
launcher app main processes and Android System (`system_server`). In
Auto mode, check the processes to exclude. KA remembers those exclusions
when you start a session. Auto samples up to four sources per round and
keeps the same 2,048-page total limit. Android System can also be selected
in manual mode.

Use **Build when idle** after closing the workload. KA checks idle CPU
time and available RAM before building. If the tablet is too busy, it
keeps the samples in memory so you can retry. The notification shows
progress and has actions to stop sampling, build, or discard the samples.
After completion it can start another session with the same apps.

Results show dictionary size, total usable pages, and the two largest
contributors by unique-page count. Candidate filenames include short
names for those contributors and a unique suffix. All candidates live
in `Download/KernelAdiutor` beside any existing dictionaries. Tap the
**LZ4 dictionary** card to choose a file or plain LZ4. The choice takes
effect the next time KA initializes ZRAM via Apply on Boot or **Apply
ZRAM size**. Already active boot swap is left alone. KA keeps the
contributor report in its settings under the original filename.

Keep dictionaries from personal workloads local. They can contain
fragments from the selected apps' memory. Raw samples stay in the
training service's memory and a reboot ends the session.

## Build

From the KA checkout in WSL, run `sh tools/build_zram_trainer.sh`.
The script builds an ARM64 helper with the AArch64 GCC cross compiler
and Zstandard v1.5.7 pinned to `f8745da6ff1ad1e7bab384bd1f9d742439278e99`.
Then run `gradlew.bat --offline :app:assembleDebug` on Windows. The
generated helper and upstream license are embedded in the APK.

Local artifact: `E:\KA-Trainer.apk`

APK SHA-256: `7f73e4f8bb48843a756e8d466b981d8cc629ec43827abfd28d7a0ca3bab38d5c`

Auto update: `E:\KA-Trainer-Auto.apk`

APK SHA-256: `f57d35746fc1b4a588e5118991671d402a1a033229db84f9990dbd6e2d0fa314`

## Verification

- The ZRAM setup harness passed named-file selection, plain LZ4,
  fallback, active-swap protection, and failed swapoff.
- The SM-T227U captured 768 synthetic pages, of which 474 were usable,
  and generated a 16,294-byte raw dictionary. Its live dictionary
  remained 16,384 bytes.
- The installed KA UID could start the ARM64 helper from its private
  cache through KernelSU. The new APK's interactive notification and
  picker were not exercised on the tablet.
- The debug APK signature verified and matched the previous T227U KA APK.
- The Auto update was installed on the T227U. The workload picker
  showed the Auto checkbox, exclusion mode, and the Android System
  entry. The ZRAM setup harness passed again.
