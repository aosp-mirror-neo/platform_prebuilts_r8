/*
 * Copyright (C) 2025 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tools.r8wrappers.processkeeprules;

import com.android.tools.r8.CompilationFailedException;
import com.android.tools.r8.Diagnostic;
import com.android.tools.r8.DiagnosticsHandler;
import com.android.tools.r8.processkeeprules.ProcessKeepRules;
import com.android.tools.r8.processkeeprules.ProcessKeepRulesCommand;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

public class ProcessKeepRulesWrapper {
  public static void main(String[] args) {
    if (args.length == 0) {
      System.out.println("Usage: process-keep-rules <keep-rule-files>");
      System.exit(1);
    }

    List<Diagnostic> errors = new ArrayList<>();
    ProcessKeepRulesCommand.Builder builder = ProcessKeepRulesCommand.builder(
        new DiagnosticsHandler() {
          @Override
          public void error(Diagnostic error) {
            errors.add(error);
          }
        })
        .addKeepRuleFiles(Arrays.stream(args).map(Paths::get).collect(Collectors.toList()))
        .setLibraryConsumerRuleValidation(true);

    try {
      ProcessKeepRules.run(builder.build());
    } catch (CompilationFailedException e) {
        errors.forEach(
            diagnostic ->
                System.err.println(
                    diagnostic.getOrigin()
                        + ", "
                        + diagnostic.getPosition()
                        + ": "
                        + diagnostic.getDiagnosticMessage()));
        System.exit(1);
    }
  }
}
