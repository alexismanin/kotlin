// DECLARATION_TYPE: org.jetbrains.kotlin.psi.KtClass
// MAIN_FILE_NAME: Foo
// LANGUAGE: +ValueClasses
package pack

@JvmInline
value class Foo<T>(val a: T, val b: T)