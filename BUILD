load("@rules_java//java:defs.bzl", "java_binary")
load("@rules_license//rules:license.bzl", "license")
load("@rules_license//rules:package_info.bzl", "package_info")
load("//build/bazel/rules/gathering:prebuilt_package_metadata.bzl", "prebuilt_package_metadata")
load("//tools/base/bazel:jvm_import.bzl", "jvm_import")
load("//tools/base/bazel:utils.bzl", "fileset")

package(
    default_package_metadata = [
        ":package_info",
        ":r8_license",
        ":r8_package_metadata",
    ],
)

package_info(
    name = "package_info",
    package_name = "r8",
    package_url = "https://r8.googlesource.com/r8",
)

prebuilt_package_metadata(
    name = "r8_package_metadata",
    spdx_json = "r8.spdx.json",
)

license(
    name = "r8_license",
    license_text = "NOTICE",
)

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
