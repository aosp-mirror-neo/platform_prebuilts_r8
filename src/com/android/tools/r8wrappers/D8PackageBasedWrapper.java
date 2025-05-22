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
package com.android.tools.r8wrappers;

import com.android.tools.r8.ArchiveProgramResourceProvider;
import com.android.tools.r8.CompilationFailedException;
import com.android.tools.r8.D8;
import com.android.tools.r8.D8Command;
import com.android.tools.r8.OutputMode;
import com.android.tools.r8.ProgramResource;
import com.android.tools.r8.ResourceException;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class D8PackageBasedWrapper extends D8Wrapper {

  private Set<String> packageDexPath = new HashSet<>();
  private Set<String> modPackageDexPath = new HashSet<>();
  private static final int MAX_PACKAGES_PER_SHARD = 20;
  private static final int MAX_CONCURRENT_D8_THREADS = 8;
  // Match either a single-quoted string, OR a sequence of non-whitespace characters.
  private static final String FILE_PATH_REGEX = "'([^']*)'|(\\S+)";

  private static String extractAndMaybeRemoveArgument(
      String[] remainingArgs, String argument, boolean remove) {
    String base = null;
    for (int i = 0; i < remainingArgs.length; i++) {
      if (remainingArgs[i].equals(argument)) {
        base = remainingArgs[i + 1];
        if (remove) {
          remainingArgs[i] = "";
          remainingArgs[i + 1] = "";
        }
      }
    }
    if (base == null) {
      throw new RuntimeException("Can't do sharded compilation without argument: " + argument);
    }
    return base;
  }

  @Override
  public void run(String[] remainingArgs)
      throws CompilationFailedException, ExecutionException, InterruptedException {
    String baseOutputDir = extractAndMaybeRemoveArgument(remainingArgs, "--output", true);
    int minApi = Integer.parseInt(
        extractAndMaybeRemoveArgument(remainingArgs, "--min-api", false));
    int dexMergeShardCount =
        (int) Math.ceil((double) packageDexPath.size() / MAX_PACKAGES_PER_SHARD);
    ExecutorService executorService = Executors.newWorkStealingPool(MAX_CONCURRENT_D8_THREADS);

    Set<String> packagesRecompiled = new HashSet<>(modPackageDexPath);

    compileIndividualPackages(remainingArgs, baseOutputDir, executorService, packagesRecompiled);
    mergeChangedShards(baseOutputDir, executorService, packagesRecompiled, dexMergeShardCount,
        minApi);
  }

  public String[] parseWrapperArguments(String[] args) {
    List<String> remainingArgs = new ArrayList<>();
    for (int i = 0; i < args.length; i++) {
      String arg = args[i];
      switch (arg) {
        case PACKAGE_RSP: {
          if (++i >= args.length) {
            throw new RuntimeException("Missing argument to " + PACKAGE_RSP);
          }
          packageDexPath = readPackages(Path.of(args[i]));
          break;
        }
        case MODIFIED_PACKAGE_RSP: {
          if (++i >= args.length) {
            throw new RuntimeException("Missing argument to " + MODIFIED_PACKAGE_RSP);
          }
          modPackageDexPath = readPackages(Path.of(args[i]));
          break;
        }
        default: {
          remainingArgs.add(arg);
          break;
        }
      }
    }
    return super.parseWrapperArguments(remainingArgs.toArray(new String[0]));
  }

  // This helper method reads packages from a rsp file
  private Set<String> readPackages(Path srcRspFile) {
    Set<String> packageDexPath = new HashSet<>();
    try (BufferedReader reader = new BufferedReader(new FileReader(srcRspFile.toFile()))) {
      String line;
      while ((line = reader.readLine()) != null) {
        List<String> files = new ArrayList<>();
        Pattern pattern = Pattern.compile(FILE_PATH_REGEX);
        Matcher matcher = pattern.matcher(line);
        while (matcher.find()) {
          if (matcher.group(1) != null) {
            // Group 1: Single-quoted string (without the quotes)
            files.add(matcher.group(1));
          } else {
            // Group 2: Non-whitespace sequence
            files.add(matcher.group(2));
          }
        }
        packageDexPath.addAll(files);
      }
    } catch (IOException e) {
      throw new RuntimeException("Error reading rsp file at: " + srcRspFile, e);
    }
    return packageDexPath;
  }

  // Dexes all changed packages and saves the output in the package directory relative to base
  // output directory.
  private void compileIndividualPackages(
      String[] remainingArgs,
      String baseOutputDir,
      ExecutorService executorService,
      Set<String> packagesRecompiled)
      throws CompilationFailedException, ExecutionException, InterruptedException {
    Set<ProgramResource> programResources = getProgramResources(packagesRecompiled);
    List<Future<?>> futures = new ArrayList<>();
    for (String pack : packagesRecompiled) {
      futures.add(executorService.submit(() -> {
        D8Command.Builder builder = D8Command.parse(remainingArgs, CLI_ORIGIN,
            diagnosticsHandler);
        applyWrapperArguments(builder, pack, programResources, baseOutputDir);
        R8Wrapper.applyCommonCompilerArguments(builder);
        try {
          D8Command command = builder.build();
          D8.run(command);
        } catch (CompilationFailedException e) {
          throw new RuntimeException(e);
        }
      }));
    }
    awaitFutures(futures);
  }

  private void applyWrapperArguments(
      D8Command.Builder builder, String currentPackage,
      Collection<ProgramResource> programResources, String baseOutputDir) {
    diagnosticsHandler.setWarnOnUnsupportedMainDexList(true);
    diagnosticsHandler.setPrintInfoDiagnostics(printInfoDiagnostics);
    // package based dex outputs to the package path relative to base output dir
    String packageOutputDir = baseOutputDir + "/" + currentPackage;
    // flush the package output directory, so that any effects of previous dex are removed.
    flushDirFiles(packageOutputDir);
    builder.setOutput(Paths.get(packageOutputDir), OutputMode.DexIndexed);
    builder.addProgramResourceProvider(
        () -> {
          Predicate<ProgramResource> programResourcePredicate = r -> {
            // This is java descriptor based, e.g., Lcom/android/foo/MyClasss;
            String str = r.getClassDescriptors().stream().findFirst().get();
            return currentPackage.equals(str.substring(1, str.lastIndexOf('/')));
          };
          return programResources.stream().filter(programResourcePredicate)
              .collect(Collectors.toSet());
        });
  }

  private Set<ProgramResource> getProgramResources(Set<String> packagesRecompiled) {
    Predicate<String> shouldReadEntry = className -> {
      int lastIdx = className.lastIndexOf('/');
      return packagesRecompiled.contains(className.substring(0, lastIdx != -1 ? lastIdx : 0));
    };
    Set<ProgramResource> programResources = new HashSet<>();
    for (Path noDexArchive : noDexArchives) {
      try {
        ArchiveProgramResourceProvider archiveProgramResourceProvider =
            ArchiveProgramResourceProvider.fromArchive(noDexArchive, shouldReadEntry);
        try {
          programResources.addAll(archiveProgramResourceProvider.getProgramResources());
        } finally {
          archiveProgramResourceProvider.finished(diagnosticsHandler);
        }
      } catch (ResourceException | IOException e) {
        throw new RuntimeException(e);
      }
    }
    return programResources;
  }

  // Multiple Packages are merged into a dex-merge shard, this method finds out the shards whose
  // constituent packages have changed, and remerges the changed + existing packages into the
  // merge-shard.
  // Packages are hashed into dex-merge shards statically, which means any package addition/removal
  // should trigger a full merge across all dex-shards.
  private void mergeChangedShards(
      String baseOutputDir,
      ExecutorService executorService,
      Set<String> packagesRecompiled,
      int dexMergeShardCount,
      int minApi) throws ExecutionException, InterruptedException {
    Map<Integer, Set<String>> mapping = getInitialMapping(packagesRecompiled,
        dexMergeShardCount);
    fillExistingPackagesIfNeeded(mapping, packageDexPath, dexMergeShardCount);
    List<Future<?>> mergeFutures = new ArrayList<>();
    for (Entry<Integer, Set<String>> entry : mapping.entrySet()) {
      D8Command.Builder builder = D8Command.builder();
      String shardOutput = baseOutputDir + "/shard" + entry.getKey();
      // create the shard output directory (if not already existing)
      createDir(shardOutput);
      // clear the shard output directory
      flushDirFiles(shardOutput);

      builder.setOutput(Paths.get(shardOutput), OutputMode.DexIndexed);
      builder.setDisableDesugaring(true);
      // Besides min api, we should not need to set anything, we are just merging here.
      builder.setMinApiLevel(minApi);
      entry.getValue().forEach(pack -> {
        try {
          builder.addProgramFiles(getDexFilesInDirectory(baseOutputDir + "/" + pack));
        } catch (IOException e) {
          throw new RuntimeException(e);
        }
      });
      mergeFutures.add(executorService.submit(() -> {
        try {
          D8.run(builder.build());
        } catch (CompilationFailedException e) {
          throw new RuntimeException(e);
        }
      }));
    }
    awaitFutures(mergeFutures);
    // All merged dexs are present in baseOutputDir/shard{idX}/. Copy them to dex/ and
    // renumber.
    copyAndRenameDexFiles(dexMergeShardCount, baseOutputDir);
  }

  // Returns the list of files ending with ".dex" in a directory
  public static List<Path> getDexFilesInDirectory(String path) throws IOException {
    Path dir = Paths.get(path);
    try (Stream<Path> stream = Files.list(dir)) { // Files.list is non-recursive
      return stream
          .filter(p -> p.toString().toLowerCase().endsWith(".dex")) // Filter by extension
          .collect(Collectors.toList());
    }
  }

  private static void awaitFutures(List<Future<?>> futures)
      throws ExecutionException, InterruptedException {
    for (Future future : futures) {
      future.get();
    }
  }

  // Copies .dex files located in baseOutputDir/shard{idX}/ to baseOutputDir/
  // Files are also renamed as classes.dex, classes2.dex, ..., etc.
  private static void copyAndRenameDexFiles(int dexMergeShardCount, String baseOutputDir) {
    int dexCounter = 0;
    Path outputDirPath = Paths.get(baseOutputDir);

    // remove the dex files generated from previous compilation.
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(outputDirPath, "classes*.dex")) {
      for (Path entry : stream) {
        Files.delete(entry);
      }
    } catch (IOException e) {
      throw new RuntimeException(e);
    }

    for (int i = 0; i < dexMergeShardCount; i++) {
      String sourcePathStr = baseOutputDir + "/shard" + i;
      Path sourcePath = Paths.get(sourcePathStr);

      List<String> dexEntriesToProcess = new ArrayList<>();

      if (Files.isDirectory(sourcePath)) {
        // Source is a directory, find dex files directly
        try (DirectoryStream<Path> stream =
                 Files.newDirectoryStream(sourcePath, "classes*.dex")) {
          for (Path entry : stream) {
            dexEntriesToProcess.add(entry.getFileName().toString());
          }
        } catch (IOException e) {
          throw new RuntimeException(e);
        }

        // Sort entries found in directory for consistent processing order
        Collections.sort(dexEntriesToProcess);

        for (String dexFileName : dexEntriesToProcess) {
          Path sourceDexFile = sourcePath.resolve(dexFileName);
          String destFileName = (dexCounter == 0)
              ? "classes.dex"
              : "classes" + (dexCounter + 1) + ".dex";
          Path destDexFile = outputDirPath.resolve(destFileName);
          try {
            Files.copy(sourceDexFile, destDexFile, StandardCopyOption.REPLACE_EXISTING);
            dexCounter++;
          } catch (IOException e) {
            throw new RuntimeException(e);
          }
        }
      }
    }
  }

  // Maps dex-merge shards to packages.
  private static Map<Integer, Set<String>> getInitialMapping(Set<String> packs, int count) {
    HashMap<Integer, Set<String>> initialMapping = new HashMap<>();
    for (String pack : packs) {
      int bucket = getBucket(count, pack);
      initialMapping.computeIfAbsent(bucket, ignored -> new HashSet<>()).add(pack);
    }
    return initialMapping;
  }

  // Simple String() based hash to map a package to its bucket.
  private static int getBucket(int count, String pack) {
    int i = pack.hashCode() % count;
    return i < 0 ? -i : i;
  }

  // Add non-modified packages for a shard, assuming modified packages are already present in the
  // mapping.
  private static void fillExistingPackagesIfNeeded(
      Map<Integer, Set<String>> mapping,
      Set<String> packs,
      int count) {
    for (String pack : packs) {
      int bucket = getBucket(count, pack);
      Set<String> packSet = mapping.get(bucket);
      if (packSet != null) {
        packSet.add(pack);
      }
    }
  }

  // Removes all the files in a directory
  private static void flushDirFiles(String flushDir) {
    Path dir = Paths.get(flushDir);
    try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
      for (Path entry : stream) {
        try {
          if (Files.isRegularFile(entry)) {
            Files.delete(entry);
          }
        } catch (IOException | SecurityException e) {
          throw new RuntimeException("Failed to delete: " + entry.getFileName() + " - "
              + e.getMessage(), e);
        }
      }
    } catch (IOException | SecurityException e) {
      throw new RuntimeException("Error reading directory: " + dir + " - " + e.getMessage(), e);
    }
  }

  // Create a directory if it does not exist.
  private static void createDir(String newDir) {
    Path dirPath = Paths.get(newDir);
    if (!Files.exists(dirPath)) {
      String dirPerms = "rwxr-xr-x";
      try {
        Set<PosixFilePermission> perms = PosixFilePermissions.fromString(dirPerms);
        Files.createDirectories(dirPath, PosixFilePermissions.asFileAttribute(perms));
      } catch (UnsupportedOperationException | IOException e) {
        throw new RuntimeException("Error recreating directory " + dirPath + ": " + e.getMessage(),
            e);
      }
    }
  }
}
