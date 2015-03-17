/**
 * These tests verify that correct parser macros work as expected, and that everything that
 * should be considered a valid parser macro is actually accepted.
 * They also test that the plugin doesn't interfere with the vanilla macros.
 */
class CorrectMacrosSuite extends MacroParserSuite {

  test("Simple expansion") {
    // We are actually giving 5 tokens to the parser macro: `BOF`, "hello", ` `, "world", `EOF`
    "macros.Macros.countTokens#(hello world)" shouldExpandTo "5"
  }

  test("Expand multi parameter parser macro") {
    // This macro always returns 1
    "macros.Macros.alwaysReturnOne#(hello)#(world)" shouldExpandTo "1"
  }

  test("Vanilla macros should still work") {
    // This macro always returns 1
    "macros.VanillaMacros.vanilla" shouldExpandTo "(1: Int)"
  }

  test("Accept a macro whose parameters are a supertype of `Seq[Token]`") {
    // This macro always return 1
    "macros.Macros.compatibleParameterType#(hello)" shouldExpandTo "1"
  }

  test("Accept a macro whose return type is a subtype of `Tree`") {
    // This macro always return 1
    "macros.Macros.compatibleReturnType#(hello)" shouldExpandTo "1"
  }
}