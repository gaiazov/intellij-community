// "Create extension function 'foo'" "true"
// ERROR: Unresolved reference: foo

fun test(): Int {
    return A().foo()
}

fun A.foo(): Int {
    TODO("not implemented") //To change body of created functions use File | Settings | File Templates.
}
