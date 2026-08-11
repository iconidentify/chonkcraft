#include <windows.h>

#include <ctype.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

struct search {
    const char *text;
    HWND found;
};

struct control_search {
    int id;
    HWND found;
};

static BOOL CALLBACK print_child(HWND window, LPARAM parameter) {
    char class_name[256];
    char text[1024];
    DWORD pid = 0;
    (void) parameter;

    GetWindowThreadProcessId(window, &pid);
    GetClassNameA(window, class_name, sizeof(class_name));
    GetWindowTextA(window, text, sizeof(text));
    printf("  hwnd=%p pid=%lu id=%ld visible=%d enabled=%d class=\"%s\" text=\"%s\"\n",
            (void *) window, (unsigned long) pid,
            (long) GetDlgCtrlID(window), IsWindowVisible(window), IsWindowEnabled(window),
            class_name, text);
    return TRUE;
}

static BOOL CALLBACK print_window(HWND window, LPARAM parameter) {
    char class_name[256];
    char text[1024];
    DWORD pid = 0;
    (void) parameter;

    GetWindowThreadProcessId(window, &pid);
    GetClassNameA(window, class_name, sizeof(class_name));
    GetWindowTextA(window, text, sizeof(text));
    if (!IsWindowVisible(window) && text[0] == '\0') {
        return TRUE;
    }
    printf("hwnd=%p pid=%lu visible=%d enabled=%d class=\"%s\" text=\"%s\"\n",
            (void *) window, (unsigned long) pid, IsWindowVisible(window),
            IsWindowEnabled(window), class_name, text);
    EnumChildWindows(window, print_child, 0);
    return TRUE;
}

static int contains_case_insensitive(const char *haystack, const char *needle) {
    size_t needle_length = strlen(needle);
    const char *position;
    if (needle_length == 0) {
        return 1;
    }
    for (position = haystack; *position != '\0'; position++) {
        size_t index;
        for (index = 0; index < needle_length; index++) {
            if (position[index] == '\0'
                    || tolower((unsigned char) position[index])
                            != tolower((unsigned char) needle[index])) {
                break;
            }
        }
        if (index == needle_length) {
            return 1;
        }
    }
    return 0;
}

static BOOL CALLBACK find_child(HWND window, LPARAM parameter) {
    struct search *search = (struct search *) parameter;
    char class_name[256];
    char text[1024];
    GetClassNameA(window, class_name, sizeof(class_name));
    GetWindowTextA(window, text, sizeof(text));
    if (lstrcmpiA(class_name, "Button") == 0 && IsWindowVisible(window)
            && IsWindowEnabled(window) && contains_case_insensitive(text, search->text)) {
        search->found = window;
        return FALSE;
    }
    return TRUE;
}

static BOOL CALLBACK find_window(HWND window, LPARAM parameter) {
    struct search *search = (struct search *) parameter;
    if (!IsWindowVisible(window)) {
        return TRUE;
    }
    EnumChildWindows(window, find_child, parameter);
    return search->found == NULL;
}

static BOOL CALLBACK find_control_id(HWND window, LPARAM parameter) {
    struct control_search *search = (struct control_search *) parameter;
    if (GetDlgCtrlID(window) == search->id && IsWindowVisible(window)
            && IsWindowEnabled(window)) {
        search->found = window;
        return FALSE;
    }
    return TRUE;
}

static BOOL CALLBACK find_top_window(HWND window, LPARAM parameter) {
    struct search *search = (struct search *) parameter;
    char text[1024];
    GetWindowTextA(window, text, sizeof(text));
    if (IsWindowVisible(window) && contains_case_insensitive(text, search->text)) {
        search->found = window;
        return FALSE;
    }
    return TRUE;
}

static int capture_window(HWND window, const char *path) {
    RECT rectangle;
    BITMAPINFO info;
    BITMAPFILEHEADER file_header;
    HDC source_dc = NULL;
    HDC memory_dc = NULL;
    HBITMAP bitmap = NULL;
    HGDIOBJ previous = NULL;
    HANDLE output = INVALID_HANDLE_VALUE;
    unsigned char *pixels = NULL;
    DWORD written;
    int width;
    int height;
    DWORD pixel_bytes;
    int status = 1;

    if (!GetWindowRect(window, &rectangle)) {
        return 1;
    }
    width = rectangle.right - rectangle.left;
    height = rectangle.bottom - rectangle.top;
    source_dc = GetWindowDC(window);
    memory_dc = CreateCompatibleDC(source_dc);
    bitmap = CreateCompatibleBitmap(source_dc, width, height);
    if (source_dc == NULL || memory_dc == NULL || bitmap == NULL) {
        goto cleanup;
    }
    previous = SelectObject(memory_dc, bitmap);
    if (!PrintWindow(window, memory_dc, 0)
            && !BitBlt(memory_dc, 0, 0, width, height, source_dc, 0, 0, SRCCOPY)) {
        goto cleanup;
    }

    ZeroMemory(&info, sizeof(info));
    info.bmiHeader.biSize = sizeof(BITMAPINFOHEADER);
    info.bmiHeader.biWidth = width;
    info.bmiHeader.biHeight = height;
    info.bmiHeader.biPlanes = 1;
    info.bmiHeader.biBitCount = 32;
    info.bmiHeader.biCompression = BI_RGB;
    pixel_bytes = (DWORD) width * (DWORD) height * 4;
    pixels = (unsigned char *) malloc(pixel_bytes);
    if (pixels == NULL
            || GetDIBits(memory_dc, bitmap, 0, (UINT) height, pixels, &info,
                    DIB_RGB_COLORS) == 0) {
        goto cleanup;
    }

    ZeroMemory(&file_header, sizeof(file_header));
    file_header.bfType = 0x4d42;
    file_header.bfOffBits = sizeof(BITMAPFILEHEADER) + sizeof(BITMAPINFOHEADER);
    file_header.bfSize = file_header.bfOffBits + pixel_bytes;
    output = CreateFileA(path, GENERIC_WRITE, 0, NULL, CREATE_ALWAYS,
            FILE_ATTRIBUTE_NORMAL, NULL);
    if (output == INVALID_HANDLE_VALUE
            || !WriteFile(output, &file_header, sizeof(file_header), &written, NULL)
            || !WriteFile(output, &info.bmiHeader, sizeof(info.bmiHeader), &written, NULL)
            || !WriteFile(output, pixels, pixel_bytes, &written, NULL)) {
        goto cleanup;
    }
    status = 0;

cleanup:
    if (output != INVALID_HANDLE_VALUE) {
        CloseHandle(output);
    }
    free(pixels);
    if (previous != NULL && memory_dc != NULL) {
        SelectObject(memory_dc, previous);
    }
    if (bitmap != NULL) {
        DeleteObject(bitmap);
    }
    if (memory_dc != NULL) {
        DeleteDC(memory_dc);
    }
    if (source_dc != NULL) {
        ReleaseDC(window, source_dc);
    }
    return status;
}

int main(int argc, char **argv) {
    struct search search;
    struct control_search control_search;
    if (argc == 1 || (argc == 2 && lstrcmpiA(argv[1], "list") == 0)) {
        EnumWindows(print_window, 0);
        return 0;
    }
    if (argc == 2 && lstrcmpiA(argv[1], "cursor-position") == 0) {
        POINT point;
        if (!GetCursorPos(&point)) {
            fprintf(stderr, "could not read cursor position\n");
            return 1;
        }
        printf("x=%ld y=%ld\n", (long) point.x, (long) point.y);
        return 0;
    }
    if (argc == 3 && lstrcmpiA(argv[1], "drive-type") == 0) {
        UINT type = GetDriveTypeA(argv[2]);
        static const char *const names[] = {
            "unknown", "no-root", "removable", "fixed",
            "remote", "cdrom", "ramdisk"
        };
        const char *name = type < sizeof(names) / sizeof(names[0])
                ? names[type] : "invalid";
        printf("root=\"%s\" type=%u name=%s\n", argv[2], type, name);
        return type == DRIVE_CDROM ? 0 : 1;
    }
    if (argc == 3 && lstrcmpiA(argv[1], "click-text") == 0) {
        search.text = argv[2];
        search.found = NULL;
        EnumWindows(find_window, (LPARAM) &search);
        if (search.found == NULL) {
            fprintf(stderr, "no enabled visible button contains: %s\n", search.text);
            return 1;
        }
        printf("clicking hwnd=%p text-match=\"%s\"\n",
                (void *) search.found, search.text);
        SendMessageA(search.found, BM_CLICK, 0, 0);
        return 0;
    }
    if (argc == 3 && lstrcmpiA(argv[1], "focus-title") == 0) {
        search.text = argv[2];
        search.found = NULL;
        EnumWindows(find_top_window, (LPARAM) &search);
        if (search.found == NULL) {
            fprintf(stderr, "no visible window contains: %s\n", search.text);
            return 1;
        }
        ShowWindow(search.found, SW_RESTORE);
        BringWindowToTop(search.found);
        SetForegroundWindow(search.found);
        return 0;
    }
    if (argc == 4 && lstrcmpiA(argv[1], "capture-title") == 0) {
        search.text = argv[2];
        search.found = NULL;
        EnumWindows(find_top_window, (LPARAM) &search);
        if (search.found == NULL) {
            fprintf(stderr, "no visible window contains: %s\n", search.text);
            return 1;
        }
        return capture_window(search.found, argv[3]);
    }
    if (argc == 4 && lstrcmpiA(argv[1], "key-title") == 0) {
        int key = atoi(argv[3]);
        search.text = argv[2];
        search.found = NULL;
        EnumWindows(find_top_window, (LPARAM) &search);
        if (search.found == NULL || key <= 0 || key > 255) {
            fprintf(stderr, "window or virtual key is invalid\n");
            return 1;
        }
        SendMessageA(search.found, WM_KEYDOWN, (WPARAM) key, 0);
        SendMessageA(search.found, WM_KEYUP, (WPARAM) key, 0xc0000000L);
        return 0;
    }
    if (argc == 5 && lstrcmpiA(argv[1], "click-title") == 0) {
        int x = atoi(argv[3]);
        int y = atoi(argv[4]);
        LPARAM point = MAKELPARAM(x, y);
        search.text = argv[2];
        search.found = NULL;
        EnumWindows(find_top_window, (LPARAM) &search);
        if (search.found == NULL || x < 0 || y < 0) {
            fprintf(stderr, "window or client point is invalid\n");
            return 1;
        }
        PostMessageA(search.found, WM_MOUSEMOVE, 0, point);
        PostMessageA(search.found, WM_LBUTTONDOWN, MK_LBUTTON, point);
        PostMessageA(search.found, WM_LBUTTONUP, 0, point);
        return 0;
    }
    if (argc == 4 && lstrcmpiA(argv[1], "input-click") == 0) {
        int x = atoi(argv[2]);
        int y = atoi(argv[3]);
        INPUT input[2];
        if (x < 0 || y < 0 || !SetCursorPos(x, y)) {
            fprintf(stderr, "screen point is invalid\n");
            return 1;
        }
        Sleep(100);
        ZeroMemory(input, sizeof(input));
        input[0].type = INPUT_MOUSE;
        input[0].mi.dwFlags = MOUSEEVENTF_LEFTDOWN;
        input[1].type = INPUT_MOUSE;
        input[1].mi.dwFlags = MOUSEEVENTF_LEFTUP;
        if (SendInput(2, input, sizeof(INPUT)) != 2) {
            fprintf(stderr, "could not inject mouse click error=%lu\n",
                    (unsigned long) GetLastError());
            return 1;
        }
        return 0;
    }
    if (argc == 3 && lstrcmpiA(argv[1], "input-key") == 0) {
        int key = atoi(argv[2]);
        INPUT input[2];
        if (key <= 0 || key > 255) {
            fprintf(stderr, "virtual key is invalid\n");
            return 1;
        }
        ZeroMemory(input, sizeof(input));
        input[0].type = INPUT_KEYBOARD;
        input[0].ki.wVk = (WORD) key;
        input[1].type = INPUT_KEYBOARD;
        input[1].ki.wVk = (WORD) key;
        input[1].ki.dwFlags = KEYEVENTF_KEYUP;
        if (SendInput(2, input, sizeof(INPUT)) != 2) {
            fprintf(stderr, "could not inject key error=%lu\n",
                    (unsigned long) GetLastError());
            return 1;
        }
        return 0;
    }
    if ((argc == 4 && lstrcmpiA(argv[1], "click-id") == 0)
            || (argc == 5 && lstrcmpiA(argv[1], "set-id") == 0)) {
        search.text = argv[2];
        search.found = NULL;
        EnumWindows(find_top_window, (LPARAM) &search);
        control_search.id = atoi(argv[3]);
        control_search.found = NULL;
        if (search.found != NULL && control_search.id > 0) {
            EnumChildWindows(search.found, find_control_id,
                    (LPARAM) &control_search);
        }
        if (search.found == NULL || control_search.found == NULL) {
            fprintf(stderr, "window or enabled visible control id is invalid\n");
            return 1;
        }
        if (argc == 4) {
            SendMessageA(control_search.found, BM_CLICK, 0, 0);
        } else if (!SetWindowTextA(control_search.found, argv[4])) {
            fprintf(stderr, "could not set text on control id %d\n",
                    control_search.id);
            return 1;
        }
        return 0;
    }
    fprintf(stderr, "usage: bne-ui.exe list | cursor-position | drive-type root | "
            "input-click screen-x screen-y | click-text substring | "
            "input-key virtual-key | "
            "focus-title substring | capture-title substring output.bmp | "
            "key-title substring virtual-key | click-title substring x y | "
            "click-id title-substring control-id | "
            "set-id title-substring control-id text\n");
    return 2;
}
