#include <windows.h>

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

static char *quote_argument(const char *argument) {
    size_t length = strlen(argument);
    char *quoted = (char *) malloc(length * 2 + 3);
    char *out = quoted;
    const char *in = argument;
    size_t slashes = 0;

    if (quoted == NULL) {
        return NULL;
    }
    *out++ = '"';
    while (*in != '\0') {
        if (*in == '\\') {
            slashes++;
            in++;
            continue;
        }
        if (*in == '"') {
            while (slashes > 0) {
                *out++ = '\\';
                *out++ = '\\';
                slashes--;
            }
            *out++ = '\\';
            *out++ = *in++;
            slashes = 0;
            continue;
        }
        while (slashes > 0) {
            *out++ = '\\';
            slashes--;
        }
        slashes = 0;
        *out++ = *in++;
    }
    while (slashes > 0) {
        *out++ = '\\';
        *out++ = '\\';
        slashes--;
    }
    *out++ = '"';
    *out = '\0';
    return quoted;
}

static char *build_command_line(int argc, char **argv, int first) {
    size_t capacity = 1;
    size_t used = 0;
    int index;
    char *line;

    for (index = first; index < argc; index++) {
        capacity += strlen(argv[index]) * 2 + 4;
    }
    line = (char *) malloc(capacity);
    if (line == NULL) {
        return NULL;
    }
    line[0] = '\0';
    for (index = first; index < argc; index++) {
        char *quoted = quote_argument(argv[index]);
        size_t length;
        if (quoted == NULL) {
            free(line);
            return NULL;
        }
        length = strlen(quoted);
        if (used != 0) {
            line[used++] = ' ';
        }
        memcpy(line + used, quoted, length + 1);
        used += length;
        free(quoted);
    }
    return line;
}

static HMODULE inject(HANDLE process, const char *dll_path) {
    SIZE_T path_bytes = strlen(dll_path) + 1;
    LPVOID remote_path;
    HMODULE kernel32;
    FARPROC load_library;
    HANDLE thread;
    DWORD exit_code = 0;

    remote_path = VirtualAllocEx(process, NULL, path_bytes,
            MEM_COMMIT | MEM_RESERVE, PAGE_READWRITE);
    if (remote_path == NULL) {
        fprintf(stderr, "VirtualAllocEx failed: %lu\n", (unsigned long) GetLastError());
        return NULL;
    }
    if (!WriteProcessMemory(process, remote_path, dll_path, path_bytes, NULL)) {
        fprintf(stderr, "WriteProcessMemory failed: %lu\n", (unsigned long) GetLastError());
        VirtualFreeEx(process, remote_path, 0, MEM_RELEASE);
        return NULL;
    }
    kernel32 = GetModuleHandleA("kernel32.dll");
    load_library = kernel32 == NULL ? NULL : GetProcAddress(kernel32, "LoadLibraryA");
    if (load_library == NULL) {
        fprintf(stderr, "cannot resolve LoadLibraryA\n");
        VirtualFreeEx(process, remote_path, 0, MEM_RELEASE);
        return NULL;
    }
    thread = CreateRemoteThread(process, NULL, 0,
            (LPTHREAD_START_ROUTINE) (void *) load_library,
            remote_path, 0, NULL);
    if (thread == NULL) {
        fprintf(stderr, "CreateRemoteThread failed: %lu\n", (unsigned long) GetLastError());
        VirtualFreeEx(process, remote_path, 0, MEM_RELEASE);
        return NULL;
    }
    WaitForSingleObject(thread, INFINITE);
    GetExitCodeThread(thread, &exit_code);
    CloseHandle(thread);
    VirtualFreeEx(process, remote_path, 0, MEM_RELEASE);
    if (exit_code == 0) {
        fprintf(stderr, "remote LoadLibraryA failed\n");
        return NULL;
    }
    return (HMODULE) (ULONG_PTR) exit_code;
}

static int initialize_remote(HANDLE process, HMODULE remote_module,
        const char *dll_path) {
    HMODULE local_module;
    FARPROC local_initialize;
    LPTHREAD_START_ROUTINE remote_initialize;
    HANDLE thread;
    DWORD exit_code = 0;

    local_module = LoadLibraryExA(dll_path, NULL, DONT_RESOLVE_DLL_REFERENCES);
    if (local_module == NULL) {
        fprintf(stderr, "local LoadLibraryEx failed: %lu\n",
                (unsigned long) GetLastError());
        return 1;
    }
    local_initialize = GetProcAddress(local_module, "bne_trace_init");
    if (local_initialize == NULL) {
        /* MinGW decorates 32-bit WINAPI exports unless a .def alias is used. */
        local_initialize = GetProcAddress(local_module, "bne_trace_init@4");
    }
    if (local_initialize == NULL) {
        fprintf(stderr, "tracer does not export bne_trace_init\n");
        FreeLibrary(local_module);
        return 1;
    }
    remote_initialize = (LPTHREAD_START_ROUTINE) (void *)
            ((BYTE *) remote_module
                    + ((BYTE *) (void *) local_initialize - (BYTE *) local_module));
    thread = CreateRemoteThread(process, NULL, 0, remote_initialize,
            NULL, 0, NULL);
    if (thread == NULL) {
        fprintf(stderr, "remote tracer initialization failed: %lu\n",
                (unsigned long) GetLastError());
        FreeLibrary(local_module);
        return 1;
    }
    WaitForSingleObject(thread, INFINITE);
    GetExitCodeThread(thread, &exit_code);
    CloseHandle(thread);
    FreeLibrary(local_module);
    if (exit_code == 0) {
        fprintf(stderr, "tracer initialization returned failure\n");
        return 1;
    }
    return 0;
}

int main(int argc, char **argv) {
    char dll_path[MAX_PATH];
    char target_path[MAX_PATH];
    char *command_line;
    STARTUPINFOA startup;
    PROCESS_INFORMATION process;
    DWORD result;
    HMODULE remote_module;
    int status;

    if (argc < 3) {
        fprintf(stderr, "usage: bne-inject.exe tracer.dll target.exe [arguments...]\n");
        return 2;
    }
    if (GetFullPathNameA(argv[1], MAX_PATH, dll_path, NULL) == 0
            || GetFullPathNameA(argv[2], MAX_PATH, target_path, NULL) == 0) {
        fprintf(stderr, "cannot resolve input paths: %lu\n", (unsigned long) GetLastError());
        return 2;
    }
    command_line = build_command_line(argc, argv, 2);
    if (command_line == NULL) {
        fprintf(stderr, "cannot allocate command line\n");
        return 2;
    }
    ZeroMemory(&startup, sizeof(startup));
    ZeroMemory(&process, sizeof(process));
    startup.cb = sizeof(startup);
    if (!CreateProcessA(target_path, command_line, NULL, NULL, FALSE,
            CREATE_SUSPENDED, NULL, NULL, &startup, &process)) {
        fprintf(stderr, "CreateProcess failed: %lu\n", (unsigned long) GetLastError());
        free(command_line);
        return 1;
    }
    free(command_line);

    remote_module = inject(process.hProcess, dll_path);
    status = remote_module == NULL ? 1
            : initialize_remote(process.hProcess, remote_module, dll_path);
    if (status == 0) {
        ResumeThread(process.hThread);
        WaitForSingleObject(process.hProcess, INFINITE);
        if (!GetExitCodeProcess(process.hProcess, &result)) {
            result = 1;
        }
        status = (int) result;
    } else {
        TerminateProcess(process.hProcess, 1);
    }
    CloseHandle(process.hThread);
    CloseHandle(process.hProcess);
    return status;
}
