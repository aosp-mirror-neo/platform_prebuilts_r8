load("//tools/base/bazel:jvm_import.bzl", "jvm_import")
load("//tools/base/bazel:utils.bzl", "fileset")

fileset(
    name = "license",
    srcs = ["LICENSE"],
    mappings = {"LICENSE": "r8_license.txt"},
    visibility = ["//tools/adt/idea/studio:__pkg__"],
)

# managed by go/iml_to_build
jvm_import(
    name = "r8",
    jars = ["r8.jar"],
    visibility = [
        "//prebuilts/tools/linux-x86_64/art:__pkg__",
        "//tools/adt/idea/android:__pkg__",
        "//tools/adt/idea/android-kotlin:__pkg__",
        "//tools/adt/idea/debuggers:__pkg__",
        "//tools/adt/idea/ij-debugger-tests:__pkg__",
        "//tools/adt/idea/logcat:__pkg__",
        "//tools/adt/idea/studio:__pkg__",
        "//tools/base/build-system/builder:__pkg__",
        "//tools/base/build-system/builder-r8:__pkg__",
        "//tools/base/build-system/shrinker:__pkg__",
        "//tools/base/deploy/deployer:__pkg__",
        "//tools/base/deploy/test:__pkg__",
        "//tools/base/sdklib:__pkg__",
    ],
)

java_binary(
    name = "d8",
    main_class = "com.android.tools.r8.D8",
    visibility = ["//visibility:public"],
    runtime_deps = [":r8"],
)

filegroup(
    name = "r8-jar",
    srcs = ["r8.jar"],
    visibility = ["//tools/adt/idea/ij-debugger-tests:__pkg__"],
)
