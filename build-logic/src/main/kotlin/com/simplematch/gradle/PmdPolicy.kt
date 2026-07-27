package com.simplematch.gradle

/** The deliberately small PMD design policy enforced for production Java. */
internal object PmdPolicy {
  const val RULESET_PATH = "config/pmd/simplematch-design.xml"

  val ruleReferences =
      listOf(
          "category/java/design.xml/ExcessiveParameterList",
          "category/java/design.xml/CyclomaticComplexity",
          "category/java/design.xml/NcssCount",
          "category/java/design.xml/ExcessivePublicCount",
          "category/java/design.xml/TooManyMethods")
}
