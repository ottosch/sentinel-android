#!/usr/bin/env python3
"""#576: fail if a release APK dex calls logcat past the reviewed allowlist.

Scans every classes*.dex in the APK with dexdump. A class is a hit when
one of its methods does any of:

- any java.io.PrintStream method, wherever the receiver came from
- Throwable.printStackTrace (any receiver, any overload)
- any android.util.Log method, plus LogPrinter and EventLog
- java.lang.Runtime.exec
- java.util.logging Logger or Handler
- kotlin.io.ConsoleKt print/println

An APK with no classes*.dex fails. META-INF/services/org.slf4j.spi.SLF4JServiceProvider
fails. org.slf4j.impl.StaticLoggerBinder fails unless it is the NOP binder.

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
    'androidx.camera.core.ImageProcessingUtil': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.camera.core.Logger': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.camera.core.imagecapture.TakePictureManagerImpl': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.camera.core.impl.AbstractCameraPresenceSource': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
    'androidx.camera.core.impl.DeferrableSurface': 'Third-party library logging. No wallet data. A class that is not in this list fails the build.',
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
    'com.samourai.sentinel.SentinelApplication$CrashReportingTree': 'Release tree. Redacted ExplorerProbe Log.println only.',
    'com.samourai.sentinel.SentinelApplication$CrashReportingTree$log$1': 'Coroutine that writes error_dump.log. The only Log call is the fixed dump-failure string.',
    'com.samourai.sentinel.util.OkHttpLogRedaction': "JUL filter. It redacts OkHttp records before Android's log bridge.",
    'com.sparrowwallet.hummingbird.fountain.FountainDecoder': 'Third-party hummingbird decoder. Not edited in this PR.',
    'io.matthewnelson.kmp.file.internal.fs.FsJvmAndroid$Companion': 'kmp-tor / kmp-file logging. Known follow-up, not this PR.',
    'io.matthewnelson.kmp.file.internal.fs.FsJvmAndroid$__O_CLOEXEC$2': 'kmp-tor / kmp-file logging. Known follow-up, not this PR.',
    'io.matthewnelson.kmp.file.internal.fs.ParcelFileDescriptor$Companion$INSTANCE$2': 'kmp-tor / kmp-file logging. Known follow-up, not this PR.',
    'io.matthewnelson.kmp.tor.common.core.OSInfo': 'kmp-tor / kmp-file logging. Known follow-up, not this PR.',
    'io.matthewnelson.kmp.tor.common.core.internal.ProcessRunner$Default': 'kmp-tor / kmp-file logging. Known follow-up, not this PR.',
    'io.matthewnelson.kmp.tor.runtime.core.UncaughtException$Handler$Companion$PRINT$1': 'kmp-tor / kmp-file logging. Known follow-up, not this PR.',
    'io.reactivex.exceptions.CompositeException': 'RxJava error plugin. Known follow-up, not this PR.',
    'io.reactivex.exceptions.CompositeException$WrappedPrintStream': 'RxJava error plugin. Known follow-up, not this PR.',
    'io.reactivex.plugins.RxJavaPlugins': 'RxJava error plugin. Known follow-up, not this PR.',
    'kotlin.ExceptionsKt__ExceptionsKt': 'Kotlin stdlib stack-trace helper. Not an app call site.',
    'net.zetetic.database.DatabaseUtils': 'SQLCipher logcat target. Known follow-up, not this PR.',
    'net.zetetic.database.LogcatTarget': 'SQLCipher logcat target. Known follow-up, not this PR.',
    'net.zetetic.database.sqlcipher.SQLiteDatabase': 'SQLCipher logcat target. Known follow-up, not this PR.',
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
DEX_NAME = re.compile(r"classes\d*\.dex")
SERVICE_PROVIDER = "META-INF/services/org.slf4j.spi.SLF4JServiceProvider"
BINDER = "org/slf4j/impl/StaticLoggerBinder"


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


SGET = re.compile(
    r"sget(?:-object|-boolean|-byte|-char|-short|-wide)?\s+\S+,\s+L([^;]+);\.(out|err):"
)


def method_hits(text: str) -> list[str]:
    hits = []
    for owner, field in SGET.findall(text):
        if owner == "java/lang/System" and field in {"out", "err"}:
            hits.append("System." + field)
        elif owner == "java/io/FileDescriptor" and field in {"out", "err"}:
            hits.append("FileDescriptor." + field)
    for owner, name in INVOKE.findall(text):
        if owner == "java/io/PrintStream":
            hits.append("PrintStream." + name)
        elif name.startswith("printStackTrace"):
            hits.append("printStackTrace")
        elif owner == "android/util/Log":
            hits.append("android.util.Log." + name)
        elif owner == "android/util/LogPrinter":
            hits.append("android.util.LogPrinter." + name)
        elif owner == "android/util/EventLog":
            hits.append("android.util.EventLog." + name)
        elif owner == "java/lang/Runtime" and name == "exec":
            hits.append("Runtime.exec")
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


def apk_variant(apk: Path) -> str | None:
    text = f"{apk.parent}/{apk.name}".lower()
    if "production" in text and "staging" not in text:
        return "production"
    if "staging" in text and "production" not in text:
        return "staging"
    return None


def mapping_variant(path: Path) -> str | None:
    text = str(path).lower().replace("-", "").replace("_", "")
    staging = "staging" in text
    production = "production" in text
    if production and not staging:
        return "production"
    if staging and not production:
        return "staging"
    return None


def variant_errors(apk: Path, mapping_path: Path) -> list[str]:
    """Reject a staging mapping used on a production APK, and the reverse."""
    apk_v = apk_variant(apk)
    map_v = mapping_variant(mapping_path)
    if apk_v and map_v and apk_v != map_v:
        return [
            f"{apk.name}: {map_v} mapping {mapping_path.name} does not match "
            f"the {apk_v} APK"
        ]
    return []


def binder_chunks(dumps: list[str], mapping: dict[str, str]) -> list[str]:
    """Class bodies for StaticLoggerBinder, after R8 renaming."""
    wanted = {"org.slf4j.impl.StaticLoggerBinder"}
    for obfuscated, original in mapping.items():
        if original == "org.slf4j.impl.StaticLoggerBinder":
            wanted.add(obfuscated)
    chunks = []
    for dump in dumps:
        parts = dump.split("Class descriptor")
        for chunk in parts[1:]:
            header = chunk.split("\n", 1)[0]
            match = re.search(r"'(L[^']+;)'", header)
            if not match:
                continue
            name = descriptor_to_name(match.group(1))
            original = mapping.get(name, name)
            if name in wanted or original in wanted:
                chunks.append(chunk)
    return chunks


def binding_errors(
    apk: Path, zf: zipfile.ZipFile, dumps: list[str], mapping: dict[str, str]
) -> list[str]:
    """SLF4J 2.x providers and any StaticLoggerBinder that is not NOP."""
    errors = []
    for name in zf.namelist():
        norm = name.replace("\\", "/").lstrip("./")
        if norm == SERVICE_PROVIDER or norm.endswith("/" + SERVICE_PROVIDER):
            errors.append(f"{apk.name}: {norm} is an SLF4J 2.x provider")
        if "META-INF/services/org.slf4j." in norm:
            errors.append(f"{apk.name}: {norm} is an SLF4J service provider")
        if norm.startswith("org/slf4j/") or "/org/slf4j/" in norm:
            if "NOPLoggerFactory" not in norm and not norm.endswith("/"):
                # A loose class file. The dex check covers the binder itself.
                if "StaticLoggerBinder" in norm and not norm.endswith("/"):
                    blob = zf.read(name)
                    if b"NOPLoggerFactory" not in blob:
                        errors.append(
                            f"{apk.name}: {norm} is not the NOP StaticLoggerBinder"
                        )
    # Release builds rename the binder and NOPLoggerFactory. Require the mapped
    # class, and require it to instantiate NOPLoggerFactory (by name or by
    # mapping.txt). A JDK binder renamed to X5.a fails this.
    if dumps:
        chunks = binder_chunks(dumps, mapping)
        if not chunks:
            errors.append(f"{apk.name}: StaticLoggerBinder is not in the release dex")
        for chunk in chunks:
            if not binder_is_nop(chunk, mapping):
                errors.append(
                    f"{apk.name}: StaticLoggerBinder does not return NOPLoggerFactory"
                )
    return errors


def binder_is_nop(chunk: str, mapping: dict[str, str]) -> bool:
    if "NOPLoggerFactory" in chunk:
        return True
    for desc in re.findall(r"L[^;]+;", chunk):
        name = descriptor_to_name(desc)
        if mapping.get(name, name) == "org.slf4j.helpers.NOPLoggerFactory":
            return True
    return False


def scan_apk(apk: Path, mapping_path: Path) -> tuple[dict[str, set[str]], list[str]]:
    mapping = load_mapping(mapping_path)
    found: dict[str, set[str]] = {}
    errors = variant_errors(apk, mapping_path)
    dumps: list[str] = []
    with zipfile.ZipFile(apk) as zf, tempfile.TemporaryDirectory() as tmp:
        dexes = [n for n in zf.namelist() if DEX_NAME.fullmatch(n)]
        if not dexes:
            errors.append(f"{apk.name}: release APK contains no classes*.dex")
        for name in dexes:
            dest = Path(tmp) / Path(name).name
            dest.write_bytes(zf.read(name))
            try:
                dump = dexdump(dest)
            except subprocess.CalledProcessError:
                errors.append(f"{apk.name}: {name} is not a readable dex")
                continue
            dumps.append(dump)
            for cls, kinds in scan_dump(dump, mapping).items():
                found.setdefault(cls, set()).update(kinds)
        errors.extend(binding_errors(apk, zf, dumps, mapping))
    return found, errors


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
import static java.lang.System.*;
public class DexGateSample {
  public static void leak() throws Exception {
    System.out.println("secret");
    System.out.printf("%s", "x");
    try { throw new RuntimeException("x"); }
    catch (RuntimeException e) { e.printStackTrace(); }
    android.util.Log.e("t", "m");
    java.util.logging.Logger.getLogger("a").info("b");
    Runtime.getRuntime().exec("log");
  }
}
class FileOnly {
  static void file(java.io.PrintStream ps) { ps.println("file"); }
}
class StarWrite {
  static void j2() throws Exception { err.write(new byte[]{1}); }
}
class WrappedOut {
  static void pw() { java.io.PrintWriter w = new java.io.PrintWriter(System.out); }
  static void stream() { java.io.OutputStream s = System.err; }
  static void appendable() { java.lang.Appendable a = System.out; }
  static void fd() { java.io.FileDescriptor f = java.io.FileDescriptor.err; }
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
        classes = [
            Path(tmp) / name
            for name in ("DexGateSample.class", "FileOnly.class", "StarWrite.class", "WrappedOut.class")
        ]
        subprocess.run(
            [str(d8), "--min-api", "24", "--output", tmp, *[str(c) for c in classes]],
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        leak_dex = Path(tmp) / "classes.dex"
        found = scan_dump(dexdump(leak_dex), {})
        mapping = Path(tmp) / "mapping.txt"
        mapping.write_text("")
        quiet_src = Path(tmp) / "Quiet.java"
        quiet_src.write_text("public class Quiet { public static void nop() {} }\n")
        subprocess.run(
            [javac, "--release", "8", "-d", tmp, str(quiet_src)],
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        quiet_out = Path(tmp) / "quiet"
        quiet_out.mkdir()
        subprocess.run(
            [str(d8), "--min-api", "24", "--output", str(quiet_out), str(Path(tmp) / "Quiet.class")],
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        zero = Path(tmp) / "zero.apk"
        with zipfile.ZipFile(zero, "w") as zf:
            zf.writestr("AndroidManifest.xml", "<manifest/>")
        _, zero_errs = scan_apk(zero, mapping)
        if not any("no classes*.dex" in err for err in zero_errs):
            sys.stderr.write(f"zero-dex APK was not rejected: {zero_errs}\n")
            return 1
        r1 = Path(tmp) / "r1.apk"
        with zipfile.ZipFile(r1, "w") as zf:
            zf.write(leak_dex, "classes.dex")
            zf.writestr(SERVICE_PROVIDER, "org.example.Provider\n")
        _, r1_errs = scan_apk(r1, mapping)
        if not any("SLF4J 2.x provider" in err for err in r1_errs):
            sys.stderr.write(f"SLF4J 2.x provider was not rejected: {r1_errs}\n")
            return 1
        binder = Path(tmp) / "binder.apk"
        with zipfile.ZipFile(binder, "w") as zf:
            zf.write(quiet_out / "classes.dex", "classes.dex")
            zf.writestr(BINDER + ".class", b"not-the-nop-binder")
        _, binder_errs = scan_apk(binder, mapping)
        if not any("not the NOP" in err for err in binder_errs):
            sys.stderr.write(f"non-NOP StaticLoggerBinder was not rejected: {binder_errs}\n")
            return 1
        renamed_src = Path(tmp) / "X5" / "a.java"
        renamed_src.parent.mkdir()
        renamed_src.write_text(
            "package X5; public class a { public Object getLoggerFactory() { return \"JDK14\"; } }\n"
        )
        subprocess.run(
            [javac, "--release", "8", "-d", tmp, str(renamed_src)],
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        renamed_out = Path(tmp) / "renamed"
        renamed_out.mkdir()
        subprocess.run(
            [str(d8), "--min-api", "24", "--output", str(renamed_out), str(Path(tmp) / "X5" / "a.class")],
            check=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        renamed_apk = Path(tmp) / "renamed.apk"
        renamed_map = Path(tmp) / "renamed-mapping.txt"
        renamed_map.write_text("org.slf4j.impl.StaticLoggerBinder -> X5.a:\n")
        with zipfile.ZipFile(renamed_apk, "w") as zf:
            zf.write(renamed_out / "classes.dex", "classes.dex")
        _, renamed_errs = scan_apk(renamed_apk, renamed_map)
        if not any("NOPLoggerFactory" in err for err in renamed_errs):
            sys.stderr.write(f"renamed non-NOP binder was not rejected: {renamed_errs}\n")
            return 1
        second = Path(tmp) / "classes2.apk"
        with zipfile.ZipFile(second, "w") as zf:
            zf.write(quiet_out / "classes.dex", "classes.dex")
            zf.write(leak_dex, "classes2.dex")
        second_found, _ = scan_apk(second, mapping)
        if "DexGateSample" not in second_found:
            sys.stderr.write(f"classes2.dex was not scanned: {second_found}\n")
            return 1
    needed = {
        "DexGateSample": {"PrintStream.println", "PrintStream.printf", "printStackTrace", "android.util.Log.e", "java.util.logging.Logger.info", "Runtime.exec"},
        "FileOnly": {"PrintStream.println"},
        "StarWrite": {"PrintStream.write"},
        "WrappedOut": {"System.out", "System.err", "FileDescriptor.err"},
    }
    missing = []
    for cls, kinds in needed.items():
        got = found.get(cls, set())
        if not kinds <= got:
            missing.append(f"{cls} missing {kinds - got} got {got}")
    if missing:
        sys.stderr.write("dex self-test did not flag the planted calls:\n")
        for line in missing:
            sys.stderr.write(f"  {line}\n")
        return 1
    # Unknown classes must fail the allowlist. Neutering evaluate() fails this.
    rejected = evaluate(found)
    if not any("DexGateSample" in err for err in rejected):
        sys.stderr.write(f"dex allowlist check did not reject DexGateSample: {rejected}\n")
        return 1
    print(
        "dex self-test ok: PrintStream, Log, JUL, Runtime.exec, J2 write, J3 printf, "
        "PrintWriter System.out, OutputStream System.err, Appendable, FileDescriptor.err, "
        "classes2.dex, zero-dex, a non-NOP binder and a renamed non-NOP binder were flagged"
    )
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
        found, apk_errors = scan_apk(apk, mapping)
        if args.report:
            print(f"# {apk}")
            for err in apk_errors:
                print(f"! {err}")
            for cls, kinds in sorted(found.items()):
                print(f"{cls}: {', '.join(sorted(kinds))}")
            continue
        for err in apk_errors:
            errors.append(err)
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
