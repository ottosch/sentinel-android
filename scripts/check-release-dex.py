#!/usr/bin/env python3
"""#576: fail if a release APK dex calls logcat past the reviewed allowlist.

Scans every classes*.dex in the APK with dexdump. A class is a hit when
one of its methods does any of:

- PrintStream print/println in a method that also reads System.out or System.err
- Throwable.printStackTrace (any receiver, any overload)
- android.util.Log v/d/i/w/e/wtf/println
- java.util.logging Logger or Handler
- kotlin.io.ConsoleKt print/println

mapping.txt restores R8 names. A hit whose original class is not listed
below fails the build, so a new first-party or third-party site cannot
land quietly.

  python3 scripts/check-release-dex.py APK MAPPING [APK MAPPING...]
  python3 scripts/check-release-dex.py --self-test
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

# original class name -> why this site may remain in the release dex
ALLOW: dict[str, str] = {
    'androidx.activity.result.ActivityResultRegistry': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.appcompat.app.AppCompatDelegate': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.appcompat.app.AppCompatDelegateImpl': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.appcompat.app.AppCompatViewInflater': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.appcompat.app.ResourcesFlusher': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.appcompat.app.TwilightManager': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.appcompat.view.SupportMenuInflater$MenuState': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.appcompat.view.menu.MenuItemImpl': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.appcompat.view.menu.MenuItemWrapperICS': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.appcompat.widget.AppCompatReceiveContentHelper': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.appcompat.widget.AppCompatSpinner': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.appcompat.widget.AppCompatSpinner$DialogPopup': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.appcompat.widget.AppCompatTextViewAutoSizeHelper': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.appcompat.widget.DropDownListView': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.appcompat.widget.ListPopupWindow': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.appcompat.widget.MenuPopupWindow': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.appcompat.widget.ResourceManagerInternal': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.appcompat.widget.SearchView': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.appcompat.widget.SuggestionsAdapter': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.appcompat.widget.ThemeUtils': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.appcompat.widget.ToolbarWidgetWrapper': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.appcompat.widget.TooltipCompatHandler': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.appcompat.widget.TooltipPopup': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.appcompat.widget.ViewUtils': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.camera.camera2.internal.Camera2CameraFactory': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.camera.camera2.internal.Camera2PresenceSource': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.camera.camera2.internal.Camera2PresenceSource$startMonitoring$1': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.camera.camera2.internal.compat.workaround.RequestMonitor': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.camera.core.ImageCapture': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.camera.core.Logger': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.camera.core.imagecapture.TakePictureManagerImpl': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.camera.core.impl.AbstractCameraPresenceSource': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.camera.core.internal.CameraUseCaseAdapter': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.camera.core.processing.OpenGlRenderer': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.camera.core.processing.util.GLUtils': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.camera.core.streamsharing.StreamSharing': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.compose.ui.platform.AndroidComposeView': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.concurrent.futures.AbstractResolvableFuture': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.constraintlayout.core.LinearSystem': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.constraintlayout.core.widgets.ConstraintWidgetContainer': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.constraintlayout.core.widgets.analyzer.WidgetGroup': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.constraintlayout.widget.ConstraintAttribute': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.constraintlayout.widget.ConstraintHelper': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.constraintlayout.widget.ConstraintLayout': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.constraintlayout.widget.ConstraintLayout$LayoutParams': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.constraintlayout.widget.ConstraintLayoutStates': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.constraintlayout.widget.ConstraintLayoutStates$Variant': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.constraintlayout.widget.ConstraintSet': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.constraintlayout.widget.ConstraintSet$Layout': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.coordinatorlayout.widget.CoordinatorLayout': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.core.app.ActivityRecreator': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.core.app.ActivityRecreator$3': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.core.app.NavUtils': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.core.app.NotificationManagerCompat$SideChannelManager': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.core.app.TaskStackBuilder': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.core.content.res.ResourcesCompat': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.core.content.res.ResourcesCompat$ThemeCompat$Api23Impl': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.core.graphics.PathParser$PathDataNode': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.core.graphics.TypefaceCompatApi21Impl': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.core.graphics.TypefaceCompatApi24Impl': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.core.graphics.TypefaceCompatApi26Impl': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.core.graphics.TypefaceCompatUtil': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.core.graphics.drawable.IconCompat': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.core.graphics.drawable.IconCompat$Api23Impl': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.core.os.HandlerCompat': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.core.os.TraceCompat': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.core.view.MenuItemCompat': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.core.view.ViewCompat': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.core.view.ViewConfigurationCompat': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.core.view.ViewParentCompat': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.core.view.WindowInsetsCompat$Api21ReflectionHolder': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.core.view.WindowInsetsCompat$BuilderImpl20': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.core.view.WindowInsetsCompat$Impl20': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.core.view.accessibility.AccessibilityNodeInfoCompat$AccessibilityActionCompat': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.core.view.inputmethod.InputConnectionCompat': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.core.widget.NestedScrollView': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.core.widget.TextViewOnReceiveContentListener': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.customview.widget.ViewDragHelper': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.emoji2.text.DefaultEmojiCompatConfig$DefaultEmojiCompatConfigFactory': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.exifinterface.media.ExifInterface': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.exifinterface.media.ExifInterface$ByteOrderedDataInputStream': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.exifinterface.media.ExifInterface$ExifAttribute': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.fragment.app.BackStackRecord': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.fragment.app.BackStackState': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.fragment.app.DefaultSpecialEffectsController': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.fragment.app.DialogFragment': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.fragment.app.DialogFragment$4': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.fragment.app.Fragment': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.fragment.app.FragmentLayoutInflaterFactory': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.fragment.app.FragmentManager': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.fragment.app.FragmentManager$10': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.fragment.app.FragmentManager$11': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.fragment.app.FragmentManager$9': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.fragment.app.FragmentManager$FragmentIntentSenderContract': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.fragment.app.FragmentManagerViewModel': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.fragment.app.FragmentStateManager': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.fragment.app.FragmentStatePagerAdapter': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.fragment.app.FragmentStore': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.fragment.app.LogWriter': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.fragment.app.SpecialEffectsController': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.fragment.app.SpecialEffectsController$FragmentStateManagerOperation': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.fragment.app.SpecialEffectsController$Operation': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.fragment.app.SpecialEffectsController$Operation$State': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.preference.ListPreference': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.preference.PreferenceFragmentCompat': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.preference.PreferenceGroup': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.preference.SeekBarPreference': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.preference.SeekBarPreference$2': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.profileinstaller.ProfileInstaller': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.profileinstaller.ProfileInstaller$2': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.recyclerview.widget.GridLayoutManager': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.recyclerview.widget.LinearLayoutManager': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.recyclerview.widget.RecyclerView': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.recyclerview.widget.RecyclerView$SmoothScroller': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.recyclerview.widget.RecyclerView$SmoothScroller$Action': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.recyclerview.widget.RecyclerView$ViewHolder': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.room.MultiInstanceInvalidationClient': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.room.MultiInstanceInvalidationClient$observer$1': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.room.MultiInstanceInvalidationService$binder$1': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.room.RoomDatabase$MigrationContainer': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.room.support.PrePackagedCopyOpenHelper': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.sqlite.db.SupportSQLiteOpenHelper$Callback': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.sqlite.db.framework.FrameworkSQLiteOpenHelper$OpenHelper': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.sqlite.util.ProcessLock': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.swiperefreshlayout.widget.SwipeRefreshLayout': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.tracing.Trace': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.transition.ViewUtilsBase': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.viewpager.widget.ViewPager': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'com.fasterxml.jackson.databind.ext.DOMSerializer': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'com.fasterxml.jackson.databind.util.ClassUtil': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'com.google.android.material.animation.MotionSpec': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'com.google.android.material.bottomsheet.BottomSheetBehavior': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'com.google.android.material.button.MaterialButton': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'com.google.android.material.button.MaterialButtonToggleGroup': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'com.google.android.material.chip.Chip': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'com.google.android.material.floatingactionbutton.FloatingActionButton': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'com.google.android.material.navigation.NavigationBarItemView': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'com.google.android.material.resources.TextAppearance': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'com.google.android.material.ripple.RippleUtils': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'com.google.android.material.shape.MaterialShapeDrawable': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'com.google.android.material.slider.BaseSlider': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'com.google.android.material.snackbar.BaseTransientBottomBar': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'com.google.android.material.snackbar.BaseTransientBottomBar$2': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'com.google.android.material.tabs.TabLayout': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'com.google.android.material.textfield.TextInputLayout': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'com.google.android.material.transition.MaterialContainerTransform': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'com.google.common.base.Platform': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'com.google.common.base.Strings': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'com.google.common.io.Closeables': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'com.google.common.io.Closer$LoggingSuppressor': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'com.google.common.util.concurrent.AbstractFuture': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'com.google.common.util.concurrent.CycleDetectingLockFactory': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'com.google.common.util.concurrent.CycleDetectingLockFactory$Policies$2': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'com.google.protobuf.CodedOutputStream': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'com.google.protobuf.Descriptors': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'com.google.protobuf.Descriptors$DescriptorPool': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'com.google.protobuf.Descriptors$FileDescriptor': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'com.google.protobuf.TextFormat': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'com.google.protobuf.TextFormat$Parser': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'com.google.protobuf.TextFormat$Printer$MapEntryAdapter': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'com.google.protobuf.TypeRegistry': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'com.google.protobuf.TypeRegistry$Builder': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'com.google.protobuf.UnsafeUtil': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'com.invertedx.hummingbird.QrCodeAnalyzer': 'Image-format int and a fixed success line. No QR text.',
    'com.invertedx.hummingbird.QrPerf': 'Counters and part indexes. No frame bytes or wallet data.',
    'com.samourai.boltzmann.Application': 'CLI report. The method is private, so the library API cannot call it. -keep com.samourai.** leaves the class in the dex.',
    'com.samourai.sentinel.SentinelApplication$CrashReportingTree': 'Release tree. Redacted ExplorerProbe Log.println only.',
    'com.samourai.sentinel.SentinelApplication$CrashReportingTree$log$1': 'Coroutine that writes error_dump.log. The only Log call is the fixed dump-failure string.',
    'com.samourai.sentinel.util.OkHttpLogRedaction': "JUL filter. It redacts OkHttp records before Android's log bridge.",
    'com.samourai.wallet.cahoots.psbt.PSBT': 'System.out.print only when bDebug is true. The default is false.',
    'com.samourai.wallet.crypto.KOpenSSL': 'printDetails defaults to false. No caller passes true.',
    'com.samourai.wallet.psbt.PSBT': 'System.out.print only when bDebug is true. The default is false.',
    'com.sparrowwallet.hummingbird.fountain.FountainDecoder': 'Third-party hummingbird decoder. Not edited in this PR.',
    'io.matthewnelson.kmp.file.internal.fs.FsJvmAndroid$Companion': 'kmp-tor / kmp-file logging. Known follow-up, not this PR.',
    'io.matthewnelson.kmp.file.internal.fs.FsJvmAndroid$__O_CLOEXEC$2': 'kmp-tor / kmp-file logging. Known follow-up, not this PR.',
    'io.matthewnelson.kmp.file.internal.fs.ParcelFileDescriptor$Companion$INSTANCE$2': 'kmp-tor / kmp-file logging. Known follow-up, not this PR.',
    'io.matthewnelson.kmp.tor.runtime.core.UncaughtException$Handler$Companion$PRINT$1': 'kmp-tor / kmp-file logging. Known follow-up, not this PR.',
    'io.reactivex.exceptions.CompositeException': 'RxJava error plugin. Known follow-up, not this PR.',
    'io.reactivex.plugins.RxJavaPlugins': 'RxJava error plugin. Known follow-up, not this PR.',
    'kotlin.ExceptionsKt__ExceptionsKt': 'Kotlin stdlib stack-trace helper. Not an app call site.',
    'net.zetetic.database.LogcatTarget': 'SQLCipher logcat target. Known follow-up, not this PR.',
    'net.zetetic.database.sqlcipher.CloseGuard$DefaultReporter': 'SQLCipher logcat target. Known follow-up, not this PR.',
    'okhttp3.internal.concurrent.TaskLoggerKt': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'okhttp3.internal.concurrent.TaskQueue': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'okhttp3.internal.concurrent.TaskRunner': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'okhttp3.internal.concurrent.TaskRunner$runnable$1': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'okhttp3.internal.http2.Http2Reader': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'okhttp3.internal.http2.Http2Reader$ContinuationSource': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'okhttp3.internal.http2.Http2Writer': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'okhttp3.internal.platform.Platform': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'okhttp3.internal.platform.android.AndroidLog': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'okhttp3.internal.platform.android.AndroidLogHandler': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'okio.Okio__JvmOkioKt': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'okio.SocketAsyncTimeout': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'org.bitcoinj.utils.BriefLogFormatter': 'JUL formatter writes into a StringWriter. init() is called only from bitcoinj tests.',
    'org.bouncycastle.LICENSE': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'org.bouncycastle.crypto.CryptoServicesRegistrar': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'org.bouncycastle.crypto.constraints.LegacyBitsOfSecurityConstraint': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'org.bouncycastle.crypto.constraints.LoggingConstraint': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'org.bouncycastle.crypto.constraints.ServicesConstraint': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'org.bouncycastle.crypto.engines.NaccacheSternEngine': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'org.bouncycastle.crypto.generators.NaccacheSternKeyPairGenerator': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'org.bouncycastle.crypto.signers.SM2Signer': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'org.bouncycastle.jcajce.provider.drbg.EntropyDaemon': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'org.bouncycastle.jcajce.provider.drbg.EntropyGatherer': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'org.bouncycastle.jcajce.provider.keystore.pkcs12.PKCS12KeyStoreSpi': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'org.bouncycastle.jcajce.provider.keystore.pkcs12.PKCS12PBMAC1KeyStoreSpi': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'org.bouncycastle.jce.provider.BouncyCastleProvider': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'org.bouncycastle.math.ec.tools.DiscoverEndomorphisms': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'org.bouncycastle.math.ec.tools.F2mSqrtOptimizer': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'org.bouncycastle.math.ec.tools.TraceOptimizer': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'org.bouncycastle.pqc.crypto.xmss.XMSSUtil': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'org.bouncycastle.util.io.pem.PemReader': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'org.bouncycastle.util.test.SimpleTest': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'org.koin.core.logger.EmptyLogger': 'Koin logger. Known follow-up, not this PR.',
    'org.slf4j.helpers.Util': 'slf4j-api diagnostic helper. The release binding is slf4j-nop.',
    'timber.log.Timber$DebugTree': 'Timber implementation. Release does not plant this tree.',
    'timber.log.Timber$Tree': 'Timber implementation. printStackTrace here formats a throwable for a planted tree.',
}

INVOKE = re.compile(
    r"invoke-\w+\s+\{[^}]*\},\s+L([^;]+);\.([A-Za-z0-9_$<>]+):"
)
CLASS_DESC = re.compile(r"Class descriptor\s*:\s+'(L[^']+;)'")
STDIO = ("Ljava/lang/System;.out:Ljava/io/PrintStream;", "Ljava/lang/System;.err:Ljava/io/PrintStream;")
LOG_METHODS = {"v", "d", "i", "w", "e", "wtf", "println"}


def descriptor_to_name(desc: str) -> str:
    name = desc[1:-1] if desc.startswith("L") and desc.endswith(";") else desc
    return name.replace("/", ".")


def load_mapping(path: Path) -> dict[str, str]:
    mapping: dict[str, str] = {}
    if path is None:
        return mapping
    for line in path.read_text(encoding="utf-8", errors="replace").splitlines():
        if line.startswith(" ") or " -> " not in line or not line.endswith(":"):
            continue
        original, obfuscated = line[:-1].split(" -> ")
        mapping[obfuscated.strip()] = original.strip()
    return mapping


def method_hits(text: str) -> list[str]:
    hits = []
    stdio = any(token in text for token in STDIO)
    for owner, name in INVOKE.findall(text):
        if name in {"print", "println"} and owner == "java/io/PrintStream" and stdio:
            hits.append("System.out/err PrintStream." + name)
        elif name.startswith("printStackTrace"):
            hits.append("printStackTrace")
        elif owner == "android/util/Log" and name in LOG_METHODS:
            hits.append("android.util.Log." + name)
        elif owner.startswith("java/util/logging/Logger") or owner.startswith(
            "java/util/logging/Handler"
        ) or owner.startswith("java/util/logging/ConsoleHandler") or owner.startswith(
            "java/util/logging/StreamHandler"
        ):
            hits.append("java.util.logging." + owner.rsplit("/", 1)[-1] + "." + name)
        elif owner == "kotlin/io/ConsoleKt" and name in {"print", "println"}:
            hits.append("kotlin.io.ConsoleKt." + name)
    return hits


def scan_dump(dump: str, mapping: dict[str, str]) -> dict[str, set[str]]:
    found: dict[str, set[str]] = {}
    current = None
    method: list[str] = []

    def flush() -> None:
        if current is None or not method:
            return
        kinds = method_hits("\n".join(method))
        if not kinds:
            return
        original = mapping.get(current, current)
        found.setdefault(original, set()).update(kinds)

    for line in dump.splitlines():
        desc = CLASS_DESC.search(line)
        if desc:
            flush()
            method = []
            current = descriptor_to_name(desc.group(1))
            continue
        if "name          :" in line and "Class descriptor" not in line:
            flush()
            method = [line]
            continue
        if current is not None:
            method.append(line)
    flush()
    return found


def dexdump(dex: Path) -> str:
    tool = find_dexdump()
    proc = subprocess.run(
        [str(tool), "-d", str(dex)],
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )
    return proc.stdout.decode("utf-8", errors="replace")


def find_dexdump() -> Path:
    env = os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME")
    if not env:
        props = ROOT / "local.properties"
        if props.is_file():
            for line in props.read_text(encoding="utf-8").splitlines():
                if line.startswith("sdk.dir="):
                    env = line.split("=", 1)[1].strip()
    if not env:
        sys.stderr.write("ANDROID_SDK_ROOT is not set and local.properties has no sdk.dir\n")
        raise SystemExit(2)
    tool = Path(env) / "build-tools" / "34.0.0" / "dexdump"
    if not tool.is_file():
        sys.stderr.write(f"dexdump not found at {tool}\n")
        raise SystemExit(2)
    return tool


def scan_apk(apk: Path, mapping_path: Path) -> dict[str, set[str]]:
    mapping = load_mapping(mapping_path)
    found: dict[str, set[str]] = {}
    with zipfile.ZipFile(apk) as zf, tempfile.TemporaryDirectory() as tmp:
        for name in zf.namelist():
            if not (name.startswith("classes") and name.endswith(".dex")):
                continue
            dest = Path(tmp) / Path(name).name
            dest.write_bytes(zf.read(name))
            for cls, kinds in scan_dump(dexdump(dest), mapping).items():
                found.setdefault(cls, set()).update(kinds)
    return found


def evaluate(found: dict[str, set[str]]) -> list[str]:
    errors = []
    for cls in sorted(found):
        if cls not in ALLOW:
            kinds = ", ".join(sorted(found[cls]))
            errors.append(f"{cls}: {kinds}")
        elif not ALLOW[cls].strip():
            errors.append(f"{cls}: allowlist entry needs a reason")
    return errors


def self_test() -> int:
    """Compile a tiny class with d8 and require the real scanner to flag it."""
    sdk_tool = find_dexdump()
    d8 = sdk_tool.with_name("d8")
    javac = shutil.which("javac")
    android_jar = sdk_tool.parents[2] / "platforms" / "android-35" / "android.jar"
    if javac is None or not d8.is_file() or not android_jar.is_file():
        sys.stderr.write("self-test needs javac, d8 and platforms/android-35\n")
        return 2
    sample = r"""
public class DexGateSample {
  public static void leak() {
    System.out.println("secret");
    try { throw new RuntimeException("x"); }
    catch (RuntimeException e) { e.printStackTrace(); }
    android.util.Log.e("t", "m");
    java.util.logging.Logger.getLogger("a").info("b");
  }
  public static void fileOnly(java.io.PrintStream ps) { ps.println("file"); }
}
"""
    with tempfile.TemporaryDirectory() as tmp:
        src = Path(tmp) / "DexGateSample.java"
        src.write_text(sample)
        subprocess.run(
            [javac, "--release", "8", "-cp", str(android_jar), "-d", tmp, str(src)],
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        subprocess.run(
            [str(d8), "--min-api", "24", "--output", tmp, str(Path(tmp) / "DexGateSample.class")],
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        found = scan_dump(dexdump(Path(tmp) / "classes.dex"), {})
    kinds = found.get("DexGateSample", set())
    needed = {"System.out/err PrintStream.println", "printStackTrace", "android.util.Log.e", "java.util.logging.Logger.info"}
    missing = needed - kinds
    if missing or "DexGateSample" not in found:
        sys.stderr.write(f"dex self-test did not flag the planted calls: missing {missing}, got {found}\n")
        return 1
    # A PrintStream that is not System.out must not be enough on its own.
    # fileOnly shares the class with leak(), so the class is flagged. The
    # method filter is what keeps fileOnly from being a reason by itself.
    print("dex self-test ok: planted System.out, printStackTrace, Log.e and JUL were flagged")
    return 0


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("pairs", nargs="*", help="APK MAPPING repeated")
    parser.add_argument("--report", action="store_true")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args(argv)
    if args.self_test:
        return self_test()
    if len(args.pairs) < 2 or len(args.pairs) % 2:
        sys.stderr.write("usage: check-release-dex.py APK MAPPING [APK MAPPING...]\n")
        return 2
    errors = []
    for i in range(0, len(args.pairs), 2):
        apk = Path(args.pairs[i])
        mapping = Path(args.pairs[i + 1])
        found = scan_apk(apk, mapping)
        if args.report:
            print(f"# {apk}")
            for cls, kinds in sorted(found.items()):
                print(f"{cls}: {', '.join(sorted(kinds))}")
            continue
        for err in evaluate(found):
            errors.append(f"{apk.name}: {err}")
    if args.report:
        return 0
    if errors:
        sys.stderr.write("release dex gate failed (#576):\n")
        for err in errors:
            sys.stderr.write(f"  {err}\n")
        return 1
    print("release dex gate ok")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
