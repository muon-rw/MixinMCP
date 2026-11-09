package dev.mixinmcp.tools.source

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

@Suppress("unused")
private class BytecodeSearchFixture {
    fun touch(list: MutableList<String>) {
        list.add("possibleBiomes literal")
        System.out.println(list.size)
    }
}

@Suppress("unused")
private class RunnableFixture : Runnable {
    private val counter: Long = 0

    override fun run() {}
}

class BytecodeSearchTest {

    private val fixtureName: String = BytecodeSearchFixture::class.java.name.replace('.', '/')
    private val fixtureBytes: ByteArray = classBytes(BytecodeSearchFixture::class.java)

    private fun classBytes(type: Class<*>): ByteArray =
        type.getResourceAsStream("/${type.name.replace('.', '/')}.class")!!.use { it.readBytes() }

    private fun scan(vararg needles: String, isRegex: Boolean = false): ClassHit? =
        scanClassConstants(fixtureBytes, "fixture.jar", ConstantMatcher(needles.toList(), isRegex))

    private fun scanRunnable(vararg needles: String): ClassHit =
        scanClassConstants(classBytes(RunnableFixture::class.java), "fixture.jar", ConstantMatcher(needles.toList(), isRegex = false))!!

    @Test
    fun declaredMethodsAreLabeledAsDeclarations() {
        val matches: List<ConstantMatch> = scan("touch")!!.matches
        assertEquals(listOf(ConstantMatch("declares method", "touch (Ljava/util/List;)V")), matches)
        assertEquals(MatchCategory.DECLARES, matches.single().category)
    }

    @Test
    fun declaredFieldsShowNameAndDescriptor() {
        assertTrue(scanRunnable("counter").matches.contains(ConstantMatch("declares field", "counter:J")))
    }

    @Test
    fun superclassAndInterfacesAreLabeled() {
        assertTrue(scanRunnable("java/lang/Runnable").matches.contains(ConstantMatch("implements", "java/lang/Runnable")))
        val objectMatches: List<ConstantMatch> = scanRunnable("java/lang/Object").matches
        assertTrue(objectMatches.contains(ConstantMatch("extends", "java/lang/Object")))
        assertFalse(objectMatches.contains(ConstantMatch("class ref", "java/lang/Object")))
    }

    @Test
    fun overrideIsADeclarationNotAReference() {
        val matches: List<ConstantMatch> = scanRunnable("run").matches
        assertTrue(matches.contains(ConstantMatch("declares method", "run ()V")))
        assertTrue(matches.none { it.category == MatchCategory.REFS })
    }

    @Test
    fun categoryKeysParseCaseInsensitively() {
        assertEquals(MatchCategory.DECLARES, MatchCategory.fromKey(" Declares "))
        assertNull(MatchCategory.fromKey("declarations"))
    }

    @Test
    fun fieldRefShowsOwnerNameAndDescriptor() {
        val hit: ClassHit = scan("PrintStream")!!
        assertEquals(fixtureName, hit.className)
        assertTrue(hit.matches.contains(ConstantMatch("field ref", "java/lang/System.out:Ljava/io/PrintStream;")))
    }

    @Test
    fun interfaceMethodRefMatchesOnOwner() {
        assertTrue(scan("java/util/List")!!.matches.contains(ConstantMatch("interface method ref", "java/util/List.add (Ljava/lang/Object;)Z")))
    }

    @Test
    fun dottedClassNameMatchesInternalOwnerAndHierarchy() {
        assertTrue(scan("java.util.List")!!.matches.contains(ConstantMatch("interface method ref", "java/util/List.add (Ljava/lang/Object;)Z")))
        assertTrue(scanRunnable("java.lang.Runnable").matches.contains(ConstantMatch("implements", "java/lang/Runnable")))
    }

    @Test
    fun compoundOwnerMemberPatternsMatchOnlyTargetMember() {
        for (pattern in listOf("List.add", "java.util.List.add", "List#add", "Ljava/util/List;add(Ljava/lang/Object;)Z")) {
            val matcher = ConstantMatcher(listOf(pattern), isRegex = false)
            assertTrue(pattern, matcher.mightMatch(fixtureBytes))
            val matches: List<ConstantMatch> = scanClassConstants(fixtureBytes, "fixture.jar", matcher)!!.matches
            assertEquals(pattern, listOf(ConstantMatch("interface method ref", "java/util/List.add (Ljava/lang/Object;)Z")), matches)
        }
        assertEquals(
            listOf(ConstantMatch("field ref", "java/lang/System.out:Ljava/io/PrintStream;")),
            scan("System.out")!!.matches,
        )
        assertFalse(ConstantMatcher(listOf("List.nonExistent"), isRegex = false).mightMatch(fixtureBytes))
    }

    @Test
    fun compoundPatternMatchesDeclaredMember() {
        assertEquals(
            listOf(ConstantMatch("declares method", "run ()V")),
            scanRunnable("RunnableFixture.run").matches,
        )
    }

    @Test
    fun stringLiteralIsReportedAsString() {
        assertEquals(listOf(ConstantMatch("string", "\"possibleBiomes literal\"")), scan("possibleBiomes")!!.matches)
    }

    @Test
    fun regexPatternsMatchConstants() {
        assertTrue(scan("possible\\w+ lit", isRegex = true)!!.matches.isNotEmpty())
    }

    @Test
    fun noMatchYieldsEmptyHitAndPrefilterRejects() {
        val matcher = ConstantMatcher(listOf("findClosestBiome3d"), isRegex = false)
        assertFalse(matcher.mightMatch(fixtureBytes))
        assertTrue(scanClassConstants(fixtureBytes, "fixture.jar", matcher)!!.matches.isEmpty())
    }

    @Test
    fun nonClassBytesAreRejected() {
        assertNull(scanClassConstants("not a class".toByteArray(), "x", ConstantMatcher(listOf("a"), isRegex = false)))
    }

    @Test
    fun nestedJarClassesCarryTheirContainerPath() {
        val inner = ByteArrayOutputStream()
        ZipOutputStream(inner).use { zip ->
            zip.putNextEntry(ZipEntry("$fixtureName.class"))
            zip.write(fixtureBytes)
            zip.closeEntry()
        }
        val outer: File = File.createTempFile("mixinmcp-outer", ".jar").apply { deleteOnExit() }
        ZipOutputStream(FileOutputStream(outer)).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/jarjar/inner.jar"))
            zip.write(inner.toByteArray())
            zip.closeEntry()
        }

        val stats = BytecodeScanStats()
        val hits: MutableList<ClassHit> = mutableListOf()
        scanJarBytecode(outer, null, ConstantMatcher(listOf("possibleBiomes"), isRegex = false), stats, { false }) { hits.add(it) }

        assertEquals(1, stats.nestedJarsScanned)
        assertEquals(1, stats.classesScanned)
        assertEquals(listOf("${outer.name} !/META-INF/jarjar/inner.jar"), hits.map { it.container })
    }

    @Test
    fun pathPrefixFiltersClasses() {
        val jar: File = File.createTempFile("mixinmcp-prefix", ".jar").apply { deleteOnExit() }
        ZipOutputStream(FileOutputStream(jar)).use { zip ->
            zip.putNextEntry(ZipEntry("$fixtureName.class"))
            zip.write(fixtureBytes)
            zip.closeEntry()
        }
        val hits: MutableList<ClassHit> = mutableListOf()
        scanJarBytecode(jar, "net/minecraft/", ConstantMatcher(listOf("possibleBiomes"), isRegex = false), BytecodeScanStats(), { false }) {
            hits.add(it)
        }
        assertTrue(hits.isEmpty())
    }
}
