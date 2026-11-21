# T227U Kernel Adiutor · LZ4 dictionary test

Companion app for the [SM-T227U LZ4 dictionary kernel](https://github.com/senposage/T227U-KernelSU-Next/releases/tag/r8-lz4-dictionary-test).

- Virtual Memory uses an explicit **Apply ZRAM size** action.
- At boot, KA loads `Download/KernelAdiutor/zram-lz4.dict` before
  setting ZRAM size and enabling swap. Save a nonzero size and
  enable Virtual Memory Apply on Boot.
- KA leaves pre-existing boot swap alone. An absent or rejected file
  falls back to plain LZ4.
- Fixed Android sysfs writes so each value is sent in one write.

Signed with the test debug key and uses the existing legacy Android
target. The APK and automatic 16 KiB dictionary upload have been
verified on the #22 test kernel.

Build source commit: `7932d0da50034f2a8ec195794e2dd8368eb3137c`.

APK SHA-256:
`261e80fbd0e6aa06566e92e795502d279efb8dacebc6356bc3ab2717e6e390bb`.
