package com.github.ajalt.clikt.parsers

import com.github.ajalt.clikt.core.PrintHelpMessage
import com.github.ajalt.clikt.core.subcommands
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.arguments.transformAll as argTransformAll
import com.github.ajalt.clikt.parameters.arguments.validate as argValidate
import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.defaultLazy
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import com.github.ajalt.clikt.parameters.options.transformAll as optTransformAll
import com.github.ajalt.clikt.parameters.options.validate
import com.github.ajalt.clikt.testing.TestCommand
import com.github.ajalt.clikt.testing.parse
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlin.js.JsName
import kotlin.test.Test

@Suppress("unused")
class FinalizationOrderTest {
    // Eager options (such as the auto-registered --help) are finalized breadth-first across the
    // whole invocation tree before any non-eager parameter, so a required option on the parent
    // does not prevent --help on a subcommand from winning.
    @[Test JsName("subcommand_help_wins_over_required_parent_option")]
    fun `subcommand help wins over required parent option`() {
        class Sub : TestCommand(called = false)
        class Parent : TestCommand(called = false) {
            val req by option().required()
            init {
                subcommands(Sub())
            }
        }

        val error = shouldThrow<PrintHelpMessage> {
            Parent().parse("sub --help")
        }
        error.statusCode shouldBe 0
        error.error shouldBe false
    }

    @[Test JsName("transform_all_order_is_argument_then_option_then_group_option")]
    fun `transformAll order is argument then option then group option`() {
        val events = mutableListOf<String>()

        class Group : OptionGroup() {
            val grouped by option()
                .optTransformAll {
                    events += "group-option"
                    it.lastOrNull() ?: "g"
                }
        }

        class C : TestCommand() {
            val group by Group()
            val arg by argument()
                .argTransformAll {
                    events += "argument"
                    it.single()
                }
            val opt by option()
                .optTransformAll {
                    events += "option"
                    it.lastOrNull() ?: "o"
                }
        }

        C().parse("foo")
        events shouldBe listOf("argument", "option", "group-option")
    }

    @[Test JsName("post_validate_order_is_option_then_group_option_then_argument")]
    fun `postValidate order is option then group option then argument`() {
        val events = mutableListOf<String>()

        class Group : OptionGroup() {
            val grouped by option().default("g")
                .validate { events += "group-option" }
        }

        class C : TestCommand() {
            val group by Group()
            val arg by argument().argValidate { events += "argument" }
            val opt by option().default("o").validate { events += "option" }
        }

        C().parse("foo")
        events shouldBe listOf("option", "group-option", "argument")
    }

    @[Test JsName("mutually_referencing_default_lazy_options_never_get_a_value")]
    fun `mutually referencing defaultLazy options never get a value`() {
        class C : TestCommand(called = false) {
            val a: String by option().defaultLazy { b }
            val b: String by option().defaultLazy { a }
        }

        val error = shouldThrow<IllegalStateException> {
            C().parse("")
        }
        error.message shouldBe "Cannot read from option delegate before parsing command line"
    }

    @[Test JsName("default_lazy_referencing_defaulted_option_works_in_either_declaration_order")]
    fun `defaultLazy referencing a defaulted option works in either declaration order`() {
        class LazyFirst : TestCommand() {
            val lazy by option().defaultLazy { plain }
            val plain by option().default("x")
            override fun run_() {
                plain shouldBe "x"
                lazy shouldBe "x"
            }
        }

        class LazyLast : TestCommand() {
            val plain by option().default("x")
            val lazy by option().defaultLazy { plain }
            override fun run_() {
                plain shouldBe "x"
                lazy shouldBe "x"
            }
        }

        LazyFirst().parse("")
        LazyLast().parse("")
    }
}
