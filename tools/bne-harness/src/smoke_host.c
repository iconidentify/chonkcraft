#include <windows.h>

#include <stdio.h>

typedef void (*init_function)(void);
typedef void (*screen_function)(unsigned int);

int main(int argc, char **argv) {
    HMODULE module;
    init_function init;
    screen_function screen;

    /* With no DLL this is a harmless target for the suspended-process injector. */
    if (argc == 1) {
        return 0;
    }
    if (argc != 2) {
        fprintf(stderr, "usage: bne-smoke-host.exe [path-to-bne-trace.dll]\n");
        return 2;
    }
    module = LoadLibraryA(argv[1]);
    if (module == NULL) {
        fprintf(stderr, "LoadLibrary failed: %lu\n", (unsigned long) GetLastError());
        return 1;
    }
    init = (init_function) (void *) GetProcAddress(module, "w2p_init");
    screen = (screen_function) (void *) GetProcAddress(module, "screen_update");
    if (init == NULL || screen == NULL) {
        fprintf(stderr, "tracer exports are incomplete\n");
        FreeLibrary(module);
        return 1;
    }
    init();
    screen(7);
    FreeLibrary(module);
    return 0;
}
