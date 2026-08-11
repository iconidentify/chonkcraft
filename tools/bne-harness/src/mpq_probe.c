#include <windows.h>

#include <stdio.h>

typedef BOOL (WINAPI *open_archive_fn)(const char *, DWORD, DWORD, HANDLE *);
typedef BOOL (WINAPI *close_archive_fn)(HANDLE);
typedef BOOL (WINAPI *open_file_fn)(HANDLE, const char *, DWORD, HANDLE *);
typedef BOOL (WINAPI *close_file_fn)(HANDLE);

static FARPROC ordinal(HMODULE module, WORD number) {
    return GetProcAddress(module, MAKEINTRESOURCEA(number));
}

int main(int argc, char **argv) {
    HMODULE storm;
    open_archive_fn open_archive;
    close_archive_fn close_archive;
    open_file_fn open_file;
    close_file_fn close_file;
    HANDLE archive = NULL;
    HANDLE file = NULL;
    BOOL archive_ok;
    BOOL file_ok;
    DWORD archive_error;
    DWORD file_error;

    if (argc != 3) {
        fprintf(stderr, "usage: bne-mpq-probe.exe archive inner-file\n");
        return 2;
    }
    storm = LoadLibraryA("storm.dll");
    if (storm == NULL) {
        fprintf(stderr, "could not load storm.dll error=%lu\n",
                (unsigned long) GetLastError());
        return 1;
    }
    open_archive = (open_archive_fn) ordinal(storm, 266);
    close_archive = (close_archive_fn) ordinal(storm, 252);
    open_file = (open_file_fn) ordinal(storm, 268);
    close_file = (close_file_fn) ordinal(storm, 253);
    if (open_archive == NULL || close_archive == NULL
            || open_file == NULL || close_file == NULL) {
        fprintf(stderr, "required Storm ordinals are missing\n");
        FreeLibrary(storm);
        return 1;
    }

    SetLastError(ERROR_SUCCESS);
    archive_ok = open_archive(argv[1], 1000, 1, &archive);
    archive_error = GetLastError();
    printf("archive=\"%s\" opened=%d handle=%p error=%lu\n",
            argv[1], archive_ok, (void *) archive,
            (unsigned long) archive_error);
    if (!archive_ok) {
        FreeLibrary(storm);
        return 1;
    }

    SetLastError(ERROR_SUCCESS);
    file_ok = open_file(archive, argv[2], 0, &file);
    file_error = GetLastError();
    printf("file=\"%s\" opened=%d handle=%p error=%lu\n",
            argv[2], file_ok, (void *) file, (unsigned long) file_error);
    if (file_ok) {
        close_file(file);
    }
    close_archive(archive);
    FreeLibrary(storm);
    return file_ok ? 0 : 1;
}
