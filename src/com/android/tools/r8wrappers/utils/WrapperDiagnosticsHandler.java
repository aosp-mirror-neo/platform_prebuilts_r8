// Copyright (c) 2023, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8wrappers.utils;

import com.android.tools.r8.Diagnostic;
import com.android.tools.r8.DiagnosticsHandler;
import com.android.tools.r8.DiagnosticsLevel;
import com.android.tools.r8.errors.DuplicateTypeInProgramAndLibraryDiagnostic;
import com.android.tools.r8.errors.UnsupportedMainDexListUsageDiagnostic;

public class WrapperDiagnosticsHandler implements DiagnosticsHandler {

  private boolean printInfoDiagnostics = false;
  private boolean warnOnUnsupportedMainDexList = false;
  private DiagnosticsLevel duplicateTypesLevel = DiagnosticsLevel.INFO;

  public void setPrintInfoDiagnostics(boolean value) {
    printInfoDiagnostics = value;
  }

  public void setWarnOnUnsupportedMainDexList(boolean value) {
    warnOnUnsupportedMainDexList = value;
  }

  public void setDuplicateTypesDiagnosticsLevel(DiagnosticsLevel level) {
    duplicateTypesLevel = level;
  }

  @Override
  public DiagnosticsLevel modifyDiagnosticsLevel(DiagnosticsLevel level, Diagnostic diagnostic) {
    if (warnOnUnsupportedMainDexList
        && diagnostic instanceof UnsupportedMainDexListUsageDiagnostic) {
      return DiagnosticsLevel.WARNING;
    }
    if (diagnostic instanceof DuplicateTypeInProgramAndLibraryDiagnostic) {
      level = duplicateTypesLevel;
      if ((level == DiagnosticsLevel.WARNING || level == DiagnosticsLevel.ERROR) &&
          isBenignDuplicateType((DuplicateTypeInProgramAndLibraryDiagnostic) diagnostic)) {
        level = DiagnosticsLevel.INFO;
      }
    }
    if (!printInfoDiagnostics && level == DiagnosticsLevel.INFO) {
      return DiagnosticsLevel.NONE;
    }
    return level;
  }

  private static boolean isBenignDuplicateType(
      DuplicateTypeInProgramAndLibraryDiagnostic diagnostic) {
    // The Jetpack annotation lib statically links duplicate Kotlin deps. As the annotation lib is
    // unused at runtime, treat it as benign for now.
    // TODO(b/326561340): Resolve this static duplication in Jetpack module updates.
    final boolean isKotlinAnnotationDuplicate =
        diagnostic.getType().getTypeName().startsWith("kotlin") &&
        diagnostic.getLibraryOrigin().toString().contains("androidx.annotation_annotation");
    return isKotlinAnnotationDuplicate;
  }
}
