package dev.mixinmcp.resolve

import com.intellij.testFramework.fixtures.LightJavaCodeInsightFixtureTestCase

class ClassSuggestionTest : LightJavaCodeInsightFixtureTestCase() {

    fun testWrongPackageSuggestsTheClassesWithThatSimpleName() {
        myFixture.addClass("package com.crispytwig.naturalist.server.entity.mob; public class Bird {}")
        myFixture.addClass("package other.birds; public class Bird {}")
        assertEquals(
            listOf("com.crispytwig.naturalist.server.entity.mob.Bird", "other.birds.Bird"),
            FqcnResolver.sameSimpleName(project, "com.crispytwig.naturalist.entity.Bird"),
        )
    }

    fun testBareLowercaseNameFindsTheCapitalizedClass() {
        myFixture.addClass("package a.b; public class Bear {}")
        assertEquals(listOf("a.b.Bear"), FqcnResolver.sameSimpleName(project, "bear"))
    }

    fun testMessageNamesTheCandidates() {
        myFixture.addClass("package a.b; public class Bear {}")
        val message: String = FqcnResolver.notFoundMessage(project, "x.Bear")
        assertTrue(message, message.startsWith("Class not found: x.Bear. Classes with that simple name: a.b.Bear. "))
    }

    fun testUnknownNameSuggestsNothing() {
        assertEquals(emptyList<String>(), FqcnResolver.sameSimpleName(project, "zz.NoSuchClassAnywhere"))
    }
}
