// Copyright (c) 2023, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8wrappers.utils;

import com.android.tools.r8.Diagnostic;
import com.android.tools.r8.DiagnosticsHandler;
import com.android.tools.r8.DiagnosticsLevel;
import com.android.tools.r8.errors.UnsupportedMainDexListUsageDiagnostic;

public class WrapperDiagnosticsHandler implements DiagnosticsHandler {

  private boolean printInfoDiagnostics = false;
  private boolean warnOnUnsupportedMainDexList = false;

  public void setPrintInfoDiagnostics(boolean value) {
    printInfoDiagnostics = value;
  }

  public void setWarnOnUnsupportedMainDexList(boolean value) {
    warnOnUnsupportedMainDexList = value;
  }

  @Override
  public DiagnosticsLevel modifyDiagnosticsLevel(DiagnosticsLevel level, Diagnostic diagnostic) {
    if (warnOnUnsupportedMainDexList
        && diagnostic instanceof UnsupportedMainDexListUsageDiagnostic) {
      return DiagnosticsLevel.WARNING;
    }
    if (!printInfoDiagnostics && level == DiagnosticsLevel.INFO) {
      return DiagnosticsLevel.NONE;
    }
    return level;
  }
}
