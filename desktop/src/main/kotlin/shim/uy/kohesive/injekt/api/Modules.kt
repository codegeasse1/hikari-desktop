package uy.kohesive.injekt.api

abstract class InjektScopedMain(val scope: InjektScope) : InjektModule {
    init {
        scope.registrar.registerInjectables()
    }
}

interface InjektModule {
    fun registerWith(intoModule: InjektRegistrar) {
        intoModule.registerInjectables()
    }

    fun InjektRegistrar.registerInjectables()
}
