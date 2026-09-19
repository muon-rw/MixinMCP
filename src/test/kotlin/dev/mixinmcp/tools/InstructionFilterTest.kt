package dev.mixinmcp.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InstructionFilterTest {

    private val dump: String = listOf(
        "  // access: public",
        "  // descriptor: (Lnet/minecraft/world/entity/LivingEntity;)V",
        "",
        "   L0",
        "    LINENUMBER 10 L0",
        "    ALOAD 0",
        "    INVOKEVIRTUAL net/minecraft/world/entity/LivingEntity.forEachModifier (Ljava/util/function/BiConsumer;)V",
        "   L1",
        "    LINENUMBER 12 L1",
        "    INVOKEINTERFACE net/minecraft/Api.hurt ()V (itf)",
        "    INVOKEVIRTUAL net/minecraft/world/entity/LivingEntity.forEachModifier (Ljava/util/function/BiConsumer;)V",
        "   FRAME SAME",
        "    INVOKEDYNAMIC accept(Ljava/lang/Object;)Ljava/util/function/BiConsumer; [",
        "      // handle kind 0x6 : INVOKESTATIC",
        "      java/lang/invoke/LambdaMetafactory.metafactory(...)",
        "      // arguments:",
        "      (Ljava/lang/Object;Ljava/lang/Object;)V, ",
        "      // handle kind 0x6 : INVOKESTATIC",
        "      net/minecraft/X.lambda\$tick\$3(Ljava/lang/Object;)V, ",
        "      (Ljava/lang/Object;)V",
        "    ]",
        "    RETURN",
        "    LOCALVARIABLE this Lnet/minecraft/X; L0 L1 0",
        "    MAXSTACK = 2",
    ).joinToString("\n")

    @Test
    fun repeatedTargetsCarryTheirOrdinalsAndSourceLines() {
        assertEquals(
            "  line 10: INVOKEVIRTUAL net/minecraft/world/entity/LivingEntity.forEachModifier (Ljava/util/function/BiConsumer;)V  [ordinal 0 of 2]\n" +
                "  line 12: INVOKEVIRTUAL net/minecraft/world/entity/LivingEntity.forEachModifier (Ljava/util/function/BiConsumer;)V  [ordinal 1 of 2]\n",
            filterInstructions(dump, Regex("forEachModifier")),
        )
    }

    @Test
    fun singleOccurrenceHasNoOrdinal() {
        assertEquals("  line 12: INVOKEINTERFACE net/minecraft/Api.hurt ()V (itf)\n", filterInstructions(dump, Regex("hurt")))
    }

    @Test
    fun metadataIsNotInstructions() {
        assertNull(filterInstructions(dump, Regex("LOCALVARIABLE|MAXSTACK|FRAME")))
        assertEquals(6, countInstructions(dump))
    }

    @Test
    fun lambdaNameFindsTheInvokeDynamicThatBindsIt() {
        assertEquals(
            "  line 12: INVOKEDYNAMIC accept(Ljava/lang/Object;)Ljava/util/function/BiConsumer;  -> net/minecraft/X.lambda\$tick\$3(Ljava/lang/Object;)V\n",
            filterInstructions(dump, Regex("lambda\\\$tick\\\$3")),
        )
    }

    @Test
    fun invokeDynamicWithoutHandleArgumentsShowsItsArguments() {
        val concat: String = listOf(
            "    INVOKEDYNAMIC makeConcatWithConstants(Ljava/lang/String;)Ljava/lang/String; [",
            "      // handle kind 0x6 : INVOKESTATIC",
            "      java/lang/invoke/StringConcatFactory.makeConcatWithConstants(...)",
            "      // arguments:",
            "      \"\\u0001 hearts\"",
            "    ]",
            "    ARETURN",
        ).joinToString("\n")
        assertEquals(
            "  INVOKEDYNAMIC makeConcatWithConstants(Ljava/lang/String;)Ljava/lang/String;  [\"\\u0001 hearts\"]\n",
            filterInstructions(concat, Regex("hearts")),
        )
        assertEquals(2, countInstructions(concat))
    }

    @Test
    fun ordinalsRestartPerOverload() {
        val twoOverloads: String = listOf(
            "  // descriptor: ()V",
            "    INVOKESTATIC a/B.c ()V",
            "  // descriptor: (I)V",
            "    INVOKESTATIC a/B.c ()V",
            "    INVOKESTATIC a/B.c ()V",
        ).joinToString("\n")
        assertEquals(
            "  -- ()V\n  INVOKESTATIC a/B.c ()V\n  -- (I)V\n  INVOKESTATIC a/B.c ()V  [ordinal 0 of 2]\n  INVOKESTATIC a/B.c ()V  [ordinal 1 of 2]\n",
            filterInstructions(twoOverloads, Regex("a/B\\.c")),
        )
    }
}
