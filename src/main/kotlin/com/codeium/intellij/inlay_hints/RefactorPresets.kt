/*
 * Copyright Exafunction, Inc.
 */

package com.codeium.intellij.inlay_hints

import exa.codeium_common_pb.CodeiumCommon

val GENERATE_UNIT_TEST_PRESET = "Generate unit tests"

data class RefactorPreset(
    val label: String,
    val value: String? = null,
    val languageWhitelist: List<CodeiumCommon.Language> = listOf(),
    val languageBlacklist: List<CodeiumCommon.Language> = listOf()
)

val REFACTOR_PRESETS: List<RefactorPreset> =
    listOf(
        RefactorPreset("Add comments and docstrings to the code"),
        RefactorPreset(
            "Add console.log statements so that it can be easily debugged",
            languageWhitelist =
                listOf(
                    CodeiumCommon.Language.LANGUAGE_TSX,
                    CodeiumCommon.Language.LANGUAGE_JAVASCRIPT,
                    CodeiumCommon.Language.LANGUAGE_TYPESCRIPT)),
        RefactorPreset(
            "Add logging statements so that it can be easily debugged",
            languageBlacklist =
                listOf(
                    CodeiumCommon.Language.LANGUAGE_TSX,
                    CodeiumCommon.Language.LANGUAGE_JAVASCRIPT,
                    CodeiumCommon.Language.LANGUAGE_TYPESCRIPT,
                    CodeiumCommon.Language.LANGUAGE_PYTHON)),
        RefactorPreset(
            "Add print statements so that it can be easily debugged",
            languageWhitelist = listOf(CodeiumCommon.Language.LANGUAGE_PYTHON)),
        RefactorPreset(
            "Add type annotations to the code",
            value =
                "Add code annotations to this code block, including the function arguments and return type. Modify the docstring to reflect the types.",
            languageWhitelist = listOf(CodeiumCommon.Language.LANGUAGE_PYTHON)),
        RefactorPreset(
            "Clean up this code",
            value =
                "Clean up this code by standardizing variable names, removing debugging statements, improving readability, and more. Explain what you did to clean it up in a short and concise way."),
        RefactorPreset(
            "Check for bugs and null pointers",
            value =
                "Check for bugs such as null pointer references, unhandled exceptions, and more. If you don't see anything obvious, reply that things look good and that the user can reply with a stack trace to get more information."),
        RefactorPreset("Implement the code for the TODO comment"),
        RefactorPreset(
            "Fix mypy and pylint errors and warnings",
            languageWhitelist = listOf(CodeiumCommon.Language.LANGUAGE_PYTHON)),
        RefactorPreset(GENERATE_UNIT_TEST_PRESET),
        RefactorPreset(
            "Make this code strongly typed",
            value =
                "Make this code strongly typed, including the function arguments and return type. Modify the docstring to reflect the types."),
        RefactorPreset("Make this faster and more efficient"),
        RefactorPreset(
            "[React] Make this code a functional React component.",
            languageWhitelist =
                listOf(
                    CodeiumCommon.Language.LANGUAGE_JAVASCRIPT,
                    CodeiumCommon.Language.LANGUAGE_TSX)),
        RefactorPreset(
            "[React] Create a Typescript interface to define the component props",
            languageWhitelist = listOf(CodeiumCommon.Language.LANGUAGE_TSX)),
        RefactorPreset(
            "Use async / await instead of promises",
            languageWhitelist =
                listOf(
                    CodeiumCommon.Language.LANGUAGE_TSX,
                    CodeiumCommon.Language.LANGUAGE_JAVASCRIPT,
                    CodeiumCommon.Language.LANGUAGE_TYPESCRIPT)),
        RefactorPreset("Verbosely comment this code so that I can understand what's going on."))

/**
 * Returns an array of RefactorPresetType objects filtered for the given language.
 *
 * @param language
 * - The language to filter by.
 *
 * @returns An array of RefactorPresetType objects.
 */
fun getRefactorPresets(language: CodeiumCommon.Language): List<RefactorPreset> {
  return REFACTOR_PRESETS.filter { preset ->
    // If the preset is specifically for the given language, should always be shown.
    if (preset.languageWhitelist.isNotEmpty()) {
      return@filter preset.languageWhitelist.contains(language)
    }

    // If the language is blacklisted, should never be shown.
    return@filter !(preset.languageBlacklist.isNotEmpty() &&
        preset.languageBlacklist.contains(language))
  }
}

/**
 * Determines if the given `refactorInstructions` string represents a unit test.
 *
 * @param refactorInstructions
 * - The instructions to check.
 *
 * @returns Whether the instructions indicate a unit test.
 */
fun isUnitTest(refactorInstructions: String): Boolean {
  // TODO (k): Make more sophisticated in the future.
  return refactorInstructions == GENERATE_UNIT_TEST_PRESET
}
