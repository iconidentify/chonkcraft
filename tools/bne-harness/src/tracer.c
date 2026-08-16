#include <windows.h>

#include <ctype.h>
#include <stdint.h>
#include <stdarg.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "bne_202_layout.h"
#include "trace_protocol.h"

typedef void (__cdecl *game_tick_function)(void);
typedef WORD (__cdecl *main_state_function)(void);
typedef int (__cdecl *new_game_function)(int, int, int);
typedef int (__cdecl *load_scenario_function)(int, int);
typedef void (__cdecl *master_seed_function)(DWORD);
typedef int (__cdecl *sync_dispatch_function)(BYTE *, DWORD);
typedef int (__cdecl *sync_random_function)(void);
typedef int (__cdecl *async_random_function)(void);
typedef void (__cdecl *idle_function)(BYTE *);
typedef BYTE *(__cdecl *projectile_function)(BYTE *);
typedef void (__cdecl *give_order_function)(
        BYTE *, int, int, BYTE *, void *);
typedef BYTE *(__cdecl *find_unit_function)(BYTE *);
typedef BYTE *(__cdecl *find_auto_target_function)(BYTE *, char);
typedef int (__cdecl *target_score_function)(BYTE *, BYTE *);
typedef void (__cdecl *ai_home_function)(BYTE *);
typedef int (__cdecl *set_ai_behavior_function)(BYTE *, int, DWORD *);
typedef int (__cdecl *find_square_function)(
        WORD *, DWORD, WORD, DWORD, BYTE, char);
typedef int (__cdecl *check_build_site_function)(BYTE *, WORD, WORD, DWORD);
typedef void (__cdecl *no_build_function)(WORD *, DWORD);
typedef int (__cdecl *find_ai_wood_function)(BYTE *, WORD *);
typedef BOOL (WINAPI *storm_open_archive_function)(
        const char *, DWORD, DWORD, HANDLE *);
typedef BOOL (WINAPI *storm_open_file_function)(
        HANDLE, const char *, DWORD, HANDLE *);

static HANDLE trace_file = INVALID_HANDLE_VALUE;
static HANDLE state_file = INVALID_HANDLE_VALUE;
static CRITICAL_SECTION trace_lock;
static BOOL trace_lock_ready = FALSE;
static HMODULE tracer_module = NULL;
static volatile LONG screen_callbacks = 0;
static volatile LONG traced_cycles = 0;
static LONG trace_cycle_limit = 0;
static LONG branch_pause_cycle = 0;
static char branch_ready_path[MAX_PATH];
static char branch_resume_path[MAX_PATH];
static BOOL wine_cd_fallback = FALSE;
static BOOL disable_startup_tips = FALSE;
static BOOL match_ready = FALSE;
/* The synchronized dispatcher runs immediately before the first timed game
 * tick.  match_ready is intentionally not raised until that tick proves the
 * unit pool, so it is one dispatcher call too late to gate replay record zero.
 * The successful retail scenario loader is the earlier exact boundary: lobby
 * traffic is over, the PUD and unit pool exist, and no simulation cycle has
 * run. */
static BOOL replay_game_ready = FALSE;
static game_tick_function original_game_tick = NULL;
static main_state_function original_main_state = NULL;
static new_game_function original_new_game = NULL;
static load_scenario_function original_load_scenario = NULL;
static master_seed_function original_master_seed = NULL;
static sync_dispatch_function original_sync_dispatch = NULL;
static BOOL trace_sync_random_calls = FALSE;
static BOOL trace_async_random_calls = FALSE;
static BOOL trace_ai_build_state = FALSE;
static BOOL trace_no_build = FALSE;
static LONG trace_unit_slot = -1;
static idle_function original_idle = NULL;
static projectile_function original_projectile = NULL;
static give_order_function original_internal_give_order = NULL;
static ai_home_function original_ai_home = NULL;
static set_ai_behavior_function original_set_ai_behavior = NULL;
static find_square_function original_find_square = NULL;
static check_build_site_function original_check_build_site = NULL;
static no_build_function original_set_no_build = NULL;
static no_build_function original_clear_no_build = NULL;
static find_ai_wood_function original_find_ai_wood = NULL;
static LONG active_ai_home_slot = -1;
static storm_open_archive_function original_storm_open_archive = NULL;
static storm_open_file_function original_storm_open_file = NULL;
static volatile LONG bootstrap_pending = 0;
static volatile LONG bootstrap_dispatching = 0;
static WORD bootstrap_selector = 0;
static WORD bootstrap_resource = 0;
static BYTE bootstrap_orc = 0;
static char bootstrap_scenario[MAX_PATH];
static BOOL deterministic_seed_enabled = FALSE;
static DWORD deterministic_seed = 1;
static BOOL state_stream_requested = FALSE;
static BOOL state_stream_failed = FALSE;
static BOOL state_has_previous = FALSE;
static BYTE previous_unit_bytes[BNE_UNIT_LIMIT][BNE_UNIT_BYTES];
static BYTE previous_unit_live[BNE_UNIT_LIMIT];
static BYTE unit_born_this_cycle[BNE_UNIT_LIMIT];
static DWORD unit_generations[BNE_UNIT_LIMIT];
static BOOL aux_has_previous = FALSE;
static BYTE previous_bullet_bytes[BNE_BULLET_LIMIT][BNE_BULLET_BYTES];
static BYTE previous_bullet_live[BNE_BULLET_LIMIT];
static BYTE bullet_born_this_cycle[BNE_BULLET_LIMIT];
static DWORD bullet_generations[BNE_BULLET_LIMIT];
static WORD previous_map_cells[BNE_MAP_TILE_LIMIT];
static WORD previous_map_squares[BNE_MAP_TILE_LIMIT];
static WORD previous_map_size = 0;
static BYTE *replay_schedule_data = NULL;
static BYTE *replay_schedule_cursor = NULL;
static BYTE *replay_schedule_end = NULL;
static DWORD replay_schedule_records = 0;
static DWORD replay_schedule_consumed = 0;
static BOOL replay_schedule_requested = FALSE;
static BOOL replay_schedule_valid = FALSE;

static void trace_ai_build_boundaries(const char *phase, LONG phase_index);

#pragma pack(push, 1)
typedef struct state_file_header {
    BYTE magic[8];
    WORD major;
    WORD minor;
    DWORD header_bytes;
    DWORD unit_bytes;
    DWORD unit_limit;
    DWORD player_count;
    DWORD flags;
} state_file_header;

typedef struct state_chunk_header {
    BYTE tag[4];
    DWORD payload_bytes;
} state_chunk_header;

typedef struct state_cycle_header {
    DWORD cycle;
    DWORD gameplay_seed;
    DWORD pool_count;
    DWORD changed_units;
} state_cycle_header;

typedef struct state_player_record {
    DWORD controller;
    DWORD gold;
    DWORD lumber;
    DWORD oil;
} state_player_record;

typedef struct state_unit_delta {
    DWORD slot;
    DWORD generation;
    BYTE raw[BNE_UNIT_BYTES];
} state_unit_delta;

typedef struct state_aux_header {
    DWORD cycle;
    DWORD bullet_count;
    DWORD changed_bullets;
    DWORD map_size;
    DWORD changed_tiles;
} state_aux_header;

typedef struct state_player_sim_record {
    WORD food_limit;
    WORD all_units;
    WORD all_buildings;
    WORD rescued_units;
    WORD lost_units;
    WORD lost_buildings;
    WORD kills_units;
    WORD kills_buildings;
    BYTE arrows;
    BYTE swords;
    BYTE shields;
    BYTE boat_attack;
    BYTE boat_armor;
    BYTE catapult_damage;
    BYTE ranger;
    BYTE marksmanship;
    BYTE longbow;
    BYTE scouting;
    BYTE reserved[2];
    DWORD allowed_units;
    DWORD allowed_upgrades;
    DWORD allowed_spells;
    DWORD spells_learned;
} state_player_sim_record;

typedef struct state_bullet_delta {
    DWORD slot;
    DWORD generation;
    BYTE raw[BNE_BULLET_BYTES];
} state_bullet_delta;

typedef struct state_map_delta {
    DWORD index;
    WORD cell;
    WORD square;
} state_map_delta;

typedef struct replay_schedule_header {
    BYTE magic[8];
    DWORD version;
    DWORD record_count;
    BYTE schedule_sha256[32];
    BYTE snapshot_sha256[32];
} replay_schedule_header;

typedef struct replay_schedule_record {
    DWORD index;
    DWORD network_player;
    BYTE slot_status[8];
    DWORD packet_bytes;
} replay_schedule_record;
#pragma pack(pop)

_Static_assert(sizeof(state_file_header) == 32,
        "state file header must remain 32 bytes");
_Static_assert(sizeof(state_chunk_header) == 8,
        "state chunk header must remain 8 bytes");
_Static_assert(sizeof(state_cycle_header) == 16,
        "state cycle header must remain 16 bytes");
_Static_assert(sizeof(state_player_record) == 16,
        "state player record must remain 16 bytes");
_Static_assert(sizeof(state_unit_delta) == 160,
        "state unit delta must remain 160 bytes");
_Static_assert(sizeof(state_aux_header) == 20,
        "state auxiliary header must remain 20 bytes");
_Static_assert(sizeof(state_player_sim_record) == 44,
        "state player simulation record must remain 44 bytes");
_Static_assert(sizeof(state_bullet_delta) == 72,
        "state bullet delta must remain 72 bytes");
_Static_assert(sizeof(state_map_delta) == 8,
        "state map delta must remain 8 bytes");
_Static_assert(sizeof(replay_schedule_header) == 80,
        "replay schedule header must remain 80 bytes");
_Static_assert(sizeof(replay_schedule_record) == 20,
        "replay schedule record must remain 20 bytes");

#define MAX_SCRIPT_COMMANDS 1024
#define SCRIPT_COMMAND_MOVE 1
#define SCRIPT_COMMAND_STOP 2
#define SCRIPT_COMMAND_ATTACK 3
#define SCRIPT_COMMAND_HARVEST 4
#define SCRIPT_COMMAND_PATROL 5
#define SCRIPT_COMMAND_RETURN_GOODS 6
#define SCRIPT_COMMAND_REPAIR 7
#define SCRIPT_COMMAND_ATTACK_GROUND 8
#define SCRIPT_NO_TARGET 0xffffffffUL
#define SCRIPT_WORKER_TYPE_FLAGS 0x00000300UL

typedef struct script_command {
    LONG cycle;
    DWORD unit_slot;
    DWORD target_slot;
    BYTE action;
    BYTE x;
    BYTE y;
} script_command;

static script_command script_commands[MAX_SCRIPT_COMMANDS];
static DWORD script_command_count = 0;
static DWORD next_script_command = 0;
static BOOL command_file_valid = TRUE;

static BOOL executable_page_contains(const void *address);
static void trace_critter_scheduler_state(const BYTE *pool, DWORD pool_count);
static void trace_initialization_semantics(const char *phase, LONG index,
        const BYTE *pool, DWORD pool_count);

static void disable_tips_if_requested(void) {
    if (disable_startup_tips) {
        *BNE_202_OPTION_FLAGS &= ~BNE_202_OPTION_SHOW_TIPS;
    }
}

static void json_escape(const char *source, char *dest, size_t capacity) {
    size_t out = 0;
    if (capacity == 0) {
        return;
    }
    while (*source != '\0' && out + 2 < capacity) {
        unsigned char ch = (unsigned char) *source++;
        if (ch == '\\' || ch == '"') {
            dest[out++] = '\\';
            dest[out++] = (char) ch;
        } else if (ch >= 0x20) {
            dest[out++] = (char) ch;
        }
    }
    dest[out] = '\0';
}

static void trace_write(const char *format, ...) {
    char line[2048];
    DWORD written = 0;
    int length;
    va_list args;

    if (trace_file == INVALID_HANDLE_VALUE || !trace_lock_ready) {
        return;
    }
    va_start(args, format);
    length = vsnprintf(line, sizeof(line) - 2, format, args);
    va_end(args);
    if (length < 0) {
        return;
    }
    if ((size_t) length > sizeof(line) - 2) {
        length = (int) sizeof(line) - 2;
    }
    line[length++] = '\n';
    line[length] = '\0';

    EnterCriticalSection(&trace_lock);
    WriteFile(trace_file, line, (DWORD) length, &written, NULL);
    LeaveCriticalSection(&trace_lock);
}

static void digest_hex(const BYTE digest[32], char output[65]) {
    static const char digits[] = "0123456789abcdef";
    DWORD index;
    for (index = 0; index < 32; index++) {
        output[index * 2] = digits[digest[index] >> 4];
        output[index * 2 + 1] = digits[digest[index] & 0x0f];
    }
    output[64] = '\0';
}

static BOOL read_replay_schedule(void) {
    static const BYTE magic[8] = {'B', 'N', 'E', 'R', 'P', 'L', 'N', '1'};
    char path[MAX_PATH];
    DWORD path_length = GetEnvironmentVariableA(
            "CHONK_BNE_REPLAY_SCHEDULE", path, sizeof(path));
    HANDLE source;
    DWORD file_high = 0;
    DWORD file_bytes;
    DWORD total = 0;
    replay_schedule_header *header;
    BYTE *cursor;
    DWORD record_index;
    char schedule_sha256[65];
    char snapshot_sha256[65];

    if (path_length == 0) {
        replay_schedule_valid = TRUE;
        return TRUE;
    }
    replay_schedule_requested = TRUE;
    if (path_length >= sizeof(path)) {
        trace_write("# bne-trace event=replay-schedule-rejected "
                "reason=path-too-long");
        return FALSE;
    }
    source = CreateFileA(path, GENERIC_READ, FILE_SHARE_READ, NULL,
            OPEN_EXISTING, FILE_ATTRIBUTE_NORMAL, NULL);
    if (source == INVALID_HANDLE_VALUE) {
        trace_write("# bne-trace event=replay-schedule-rejected "
                "reason=open-failed error=%lu",
                (unsigned long) GetLastError());
        return FALSE;
    }
    file_bytes = GetFileSize(source, &file_high);
    if (file_bytes == INVALID_FILE_SIZE || file_high != 0
            || file_bytes < sizeof(replay_schedule_header)
            || file_bytes > 256UL * 1024UL * 1024UL) {
        CloseHandle(source);
        trace_write("# bne-trace event=replay-schedule-rejected "
                "reason=invalid-size bytes=%lu high=%lu",
                (unsigned long) file_bytes, (unsigned long) file_high);
        return FALSE;
    }
    replay_schedule_data = (BYTE *) HeapAlloc(
            GetProcessHeap(), 0, file_bytes);
    if (replay_schedule_data == NULL) {
        CloseHandle(source);
        trace_write("# bne-trace event=replay-schedule-rejected "
                "reason=allocation-failed");
        return FALSE;
    }
    while (total < file_bytes) {
        DWORD bytes_read = 0;
        if (!ReadFile(source, replay_schedule_data + total,
                file_bytes - total, &bytes_read, NULL) || bytes_read == 0) {
            CloseHandle(source);
            trace_write("# bne-trace event=replay-schedule-rejected "
                    "reason=read-failed error=%lu",
                    (unsigned long) GetLastError());
            return FALSE;
        }
        total += bytes_read;
    }
    CloseHandle(source);
    header = (replay_schedule_header *) (void *) replay_schedule_data;
    if (memcmp(header->magic, magic, sizeof(magic)) != 0
            || header->version != 1) {
        trace_write("# bne-trace event=replay-schedule-rejected "
                "reason=identity");
        return FALSE;
    }
    cursor = replay_schedule_data + sizeof(*header);
    replay_schedule_end = replay_schedule_data + file_bytes;
    for (record_index = 0; record_index < header->record_count;
            record_index++) {
        replay_schedule_record *record;
        if ((size_t) (replay_schedule_end - cursor) < sizeof(*record)) {
            trace_write("# bne-trace event=replay-schedule-rejected "
                    "reason=truncated-record index=%lu",
                    (unsigned long) record_index);
            return FALSE;
        }
        record = (replay_schedule_record *) (void *) cursor;
        cursor += sizeof(*record);
        if (record->index != record_index
                || record->packet_bytes > (DWORD) (replay_schedule_end - cursor)) {
            trace_write("# bne-trace event=replay-schedule-rejected "
                    "reason=record-shape index=%lu",
                    (unsigned long) record_index);
            return FALSE;
        }
        cursor += record->packet_bytes;
    }
    if (cursor != replay_schedule_end) {
        trace_write("# bne-trace event=replay-schedule-rejected "
                "reason=trailing-bytes");
        return FALSE;
    }
    replay_schedule_cursor = replay_schedule_data + sizeof(*header);
    replay_schedule_records = header->record_count;
    replay_schedule_valid = TRUE;
    digest_hex(header->schedule_sha256, schedule_sha256);
    digest_hex(header->snapshot_sha256, snapshot_sha256);
    trace_write("# bne-trace event=replay-schedule-loaded records=%lu "
            "schedule-sha256=%s snapshot-sha256=%s",
            (unsigned long) replay_schedule_records,
            schedule_sha256, snapshot_sha256);
    return TRUE;
}

static BOOL state_write_all(const void *data, DWORD bytes) {
    const BYTE *cursor = (const BYTE *) data;

    while (bytes > 0) {
        DWORD written = 0;
        if (!WriteFile(state_file, cursor, bytes, &written, NULL)
                || written == 0) {
            state_stream_failed = TRUE;
            return FALSE;
        }
        cursor += written;
        bytes -= written;
    }
    return TRUE;
}

static BOOL state_open(void) {
    char path[MAX_PATH];
    DWORD path_length = GetEnvironmentVariableA(
            CHONK_BNE_STATE_ENV, path, sizeof(path));
    state_file_header header;

    if (path_length == 0) {
        state_stream_requested = FALSE;
        return TRUE;
    }
    state_stream_requested = TRUE;
    if (path_length >= sizeof(path)) {
        trace_write("# bne-trace event=state-stream-rejected "
                "reason=path-too-long");
        return FALSE;
    }
    state_file = CreateFileA(path, GENERIC_WRITE, FILE_SHARE_READ, NULL,
            CREATE_NEW, FILE_ATTRIBUTE_NORMAL, NULL);
    if (state_file == INVALID_HANDLE_VALUE) {
        trace_write("# bne-trace event=state-stream-rejected "
                "reason=open-failed error=%lu",
                (unsigned long) GetLastError());
        return FALSE;
    }
    memset(&header, 0, sizeof(header));
    memcpy(header.magic, CHONK_BNE_STATE_MAGIC, sizeof(header.magic));
    header.major = CHONK_BNE_STATE_MAJOR;
    header.minor = CHONK_BNE_STATE_MINOR;
    header.header_bytes = sizeof(header);
    header.unit_bytes = BNE_UNIT_BYTES;
    header.unit_limit = BNE_UNIT_LIMIT;
    header.player_count = BNE_PLAYER_COUNT;
    header.flags = CHONK_BNE_STATE_FLAGS;
    if (!state_write_all(&header, sizeof(header))) {
        CloseHandle(state_file);
        state_file = INVALID_HANDLE_VALUE;
        trace_write("# bne-trace event=state-stream-rejected "
                "reason=header-write-failed error=%lu",
                (unsigned long) GetLastError());
        return FALSE;
    }
    trace_write("# bne-trace event=state-stream-opened "
            "schema=%u.%u unit-bytes=%u unit-limit=%u players=%u",
            CHONK_BNE_STATE_MAJOR, CHONK_BNE_STATE_MINOR,
            BNE_UNIT_BYTES, BNE_UNIT_LIMIT, BNE_PLAYER_COUNT);
    return TRUE;
}

static BOOL state_snapshot_cycle(DWORD cycle, const BYTE *pool,
        DWORD pool_count) {
    static const BYTE cycle_tag[4] = {'C', 'Y', 'C', 'L'};
    state_chunk_header chunk;
    state_cycle_header cycle_header;
    DWORD changed_units = 0;
    DWORD index;
    int player;

    if (state_file == INVALID_HANDLE_VALUE || state_stream_failed) {
        return !state_stream_requested;
    }
    if (pool == NULL || pool_count > BNE_UNIT_LIMIT) {
        state_stream_failed = TRUE;
        return FALSE;
    }
    for (index = 0; index < pool_count; index++) {
        const BYTE *unit = pool + index * BNE_UNIT_BYTES;
        BYTE flags = unit[BNE_UNIT_FLAGS3];
        BYTE live = (flags & (BNE_UNIT_FREE | BNE_UNIT_DEAD)) == 0;

        unit_born_this_cycle[index] = live && !previous_unit_live[index];
        if (unit_born_this_cycle[index]) {
            unit_generations[index]++;
        }
        previous_unit_live[index] = live;
        if (!state_has_previous
                || unit_born_this_cycle[index]
                || memcmp(previous_unit_bytes[index], unit,
                    BNE_UNIT_BYTES) != 0) {
            changed_units++;
        }
    }
    for (index = pool_count; index < BNE_UNIT_LIMIT; index++) {
        previous_unit_live[index] = 0;
        unit_born_this_cycle[index] = 0;
    }

    memcpy(chunk.tag, cycle_tag, sizeof(chunk.tag));
    chunk.payload_bytes = sizeof(cycle_header)
            + BNE_PLAYER_COUNT * sizeof(state_player_record)
            + changed_units * sizeof(state_unit_delta);
    cycle_header.cycle = cycle;
    cycle_header.gameplay_seed = *BNE_202_RANDOM_SEED;
    cycle_header.pool_count = pool_count;
    cycle_header.changed_units = changed_units;
    if (!state_write_all(&chunk, sizeof(chunk))
            || !state_write_all(&cycle_header, sizeof(cycle_header))) {
        return FALSE;
    }
    for (player = 0; player < BNE_PLAYER_COUNT; player++) {
        state_player_record record;
        record.controller = BNE_202_PLAYER_CONTROLLERS[player];
        record.gold = BNE_202_PLAYER_GOLD[player];
        record.lumber = BNE_202_PLAYER_LUMBER[player];
        record.oil = BNE_202_PLAYER_OIL[player];
        if (!state_write_all(&record, sizeof(record))) {
            return FALSE;
        }
    }
    for (index = 0; index < pool_count; index++) {
        const BYTE *unit = pool + index * BNE_UNIT_BYTES;
        if (!state_has_previous
                || unit_born_this_cycle[index]
                || memcmp(previous_unit_bytes[index], unit,
                    BNE_UNIT_BYTES) != 0) {
            state_unit_delta delta;
            delta.slot = index;
            delta.generation = unit_generations[index];
            memcpy(delta.raw, unit, BNE_UNIT_BYTES);
            if (!state_write_all(&delta, sizeof(delta))) {
                return FALSE;
            }
            memcpy(previous_unit_bytes[index], unit, BNE_UNIT_BYTES);
        }
    }
    state_has_previous = TRUE;
    return TRUE;
}

static BOOL state_snapshot_aux(DWORD cycle) {
    static const BYTE aux_tag[4] = {'A', 'U', 'X', 'L'};
    state_chunk_header chunk;
    state_aux_header aux;
    BYTE *bullet_pool = *BNE_202_BULLET_POOL_POINTER;
    DWORD bullet_count = *BNE_202_BULLET_POOL_COUNT;
    WORD map_size = *BNE_202_MAP_SIZE;
    WORD *map_cells = *BNE_202_MAP_CELLS_POINTER;
    WORD *map_squares = *BNE_202_MAP_SQUARES_POINTER;
    DWORD map_tiles;
    DWORD changed_bullets = 0;
    DWORD changed_tiles = 0;
    DWORD index;
    int player;

    if (state_file == INVALID_HANDLE_VALUE || state_stream_failed) {
        return !state_stream_requested;
    }
    if (bullet_pool == NULL || bullet_count > BNE_BULLET_LIMIT
            || map_cells == NULL || map_squares == NULL
            || map_size == 0 || map_size > BNE_MAP_LIMIT) {
        state_stream_failed = TRUE;
        return FALSE;
    }
    map_tiles = (DWORD) map_size * (DWORD) map_size;
    for (index = 0; index < bullet_count; index++) {
        const BYTE *bullet = bullet_pool + index * BNE_BULLET_BYTES;
        BYTE live = (bullet[BNE_BULLET_FLAGS] & BNE_BULLET_FREE) == 0;

        bullet_born_this_cycle[index] = live && !previous_bullet_live[index];
        if (bullet_born_this_cycle[index]) {
            bullet_generations[index]++;
        }
        previous_bullet_live[index] = live;
        if (!aux_has_previous || bullet_born_this_cycle[index]
                || memcmp(previous_bullet_bytes[index], bullet,
                    BNE_BULLET_BYTES) != 0) {
            changed_bullets++;
        }
    }
    for (index = bullet_count; index < BNE_BULLET_LIMIT; index++) {
        previous_bullet_live[index] = 0;
        bullet_born_this_cycle[index] = 0;
    }
    for (index = 0; index < map_tiles; index++) {
        if (!aux_has_previous || previous_map_size != map_size
                || previous_map_cells[index] != map_cells[index]
                || previous_map_squares[index] != map_squares[index]) {
            changed_tiles++;
        }
    }

    memcpy(chunk.tag, aux_tag, sizeof(chunk.tag));
    chunk.payload_bytes = sizeof(aux)
            + BNE_PLAYER_COUNT * sizeof(state_player_sim_record)
            + changed_bullets * sizeof(state_bullet_delta)
            + changed_tiles * sizeof(state_map_delta);
    aux.cycle = cycle;
    aux.bullet_count = bullet_count;
    aux.changed_bullets = changed_bullets;
    aux.map_size = map_size;
    aux.changed_tiles = changed_tiles;
    if (!state_write_all(&chunk, sizeof(chunk))
            || !state_write_all(&aux, sizeof(aux))) {
        return FALSE;
    }
    for (player = 0; player < BNE_PLAYER_COUNT; player++) {
        state_player_sim_record record;

        memset(&record, 0, sizeof(record));
        record.food_limit = BNE_202_PLAYER_FOOD_LIMIT[player];
        record.all_units = BNE_202_PLAYER_ALL_UNITS[player];
        record.all_buildings = BNE_202_PLAYER_ALL_BUILDINGS[player];
        record.rescued_units = BNE_202_PLAYER_RESCUED_UNITS[player];
        record.lost_units = BNE_202_PLAYER_LOST_UNITS[player];
        record.lost_buildings = BNE_202_PLAYER_LOST_BUILDINGS[player];
        record.kills_units = BNE_202_PLAYER_KILLS_UNITS[player];
        record.kills_buildings = BNE_202_PLAYER_KILLS_BUILDINGS[player];
        record.arrows = BNE_202_PLAYER_UPGRADE_ARROWS[player];
        record.swords = BNE_202_PLAYER_UPGRADE_SWORDS[player];
        record.shields = BNE_202_PLAYER_UPGRADE_SHIELDS[player];
        record.boat_attack = BNE_202_PLAYER_UPGRADE_BOAT_ATTACK[player];
        record.boat_armor = BNE_202_PLAYER_UPGRADE_BOAT_ARMOR[player];
        record.catapult_damage = BNE_202_PLAYER_UPGRADE_CATAPULT[player];
        record.ranger = BNE_202_PLAYER_UPGRADE_RANGER[player];
        record.marksmanship =
                BNE_202_PLAYER_UPGRADE_MARKSMANSHIP[player];
        record.longbow = BNE_202_PLAYER_UPGRADE_LONGBOW[player];
        record.scouting = BNE_202_PLAYER_UPGRADE_SCOUTING[player];
        record.allowed_units = BNE_202_PLAYER_ALLOWED_UNITS[player];
        record.allowed_upgrades = BNE_202_PLAYER_ALLOWED_UPGRADES[player];
        record.allowed_spells = BNE_202_PLAYER_ALLOWED_SPELLS[player];
        record.spells_learned = BNE_202_PLAYER_SPELLS_LEARNED[player];
        if (!state_write_all(&record, sizeof(record))) {
            return FALSE;
        }
    }
    for (index = 0; index < bullet_count; index++) {
        const BYTE *bullet = bullet_pool + index * BNE_BULLET_BYTES;
        if (!aux_has_previous || bullet_born_this_cycle[index]
                || memcmp(previous_bullet_bytes[index], bullet,
                    BNE_BULLET_BYTES) != 0) {
            state_bullet_delta delta;
            delta.slot = index;
            delta.generation = bullet_generations[index];
            memcpy(delta.raw, bullet, BNE_BULLET_BYTES);
            if (!state_write_all(&delta, sizeof(delta))) {
                return FALSE;
            }
            memcpy(previous_bullet_bytes[index], bullet, BNE_BULLET_BYTES);
        }
    }
    for (index = 0; index < map_tiles; index++) {
        if (!aux_has_previous || previous_map_size != map_size
                || previous_map_cells[index] != map_cells[index]
                || previous_map_squares[index] != map_squares[index]) {
            state_map_delta delta;
            delta.index = index;
            delta.cell = map_cells[index];
            delta.square = map_squares[index];
            if (!state_write_all(&delta, sizeof(delta))) {
                return FALSE;
            }
            previous_map_cells[index] = map_cells[index];
            previous_map_squares[index] = map_squares[index];
        }
    }
    previous_map_size = map_size;
    aux_has_previous = TRUE;
    return TRUE;
}

static BOOL state_close(void) {
    static const BYTE done_tag[4] = {'D', 'O', 'N', 'E'};
    BOOL complete = !state_stream_failed;

    if (state_file == INVALID_HANDLE_VALUE) {
        return !state_stream_requested;
    }
    if (complete) {
        state_chunk_header chunk;
        DWORD cycles = (DWORD) traced_cycles;
        memcpy(chunk.tag, done_tag, sizeof(chunk.tag));
        chunk.payload_bytes = sizeof(cycles);
        complete = state_write_all(&chunk, sizeof(chunk))
                && state_write_all(&cycles, sizeof(cycles));
    }
    FlushFileBuffers(state_file);
    CloseHandle(state_file);
    state_file = INVALID_HANDLE_VALUE;
    return complete;
}

static BOOL trace_open(void) {
    char path[MAX_PATH];
    char module_path[MAX_PATH];
    char target_path[MAX_PATH];
    char escaped_module[MAX_PATH * 2];
    char escaped_target[MAX_PATH * 2];
    HMODULE target = GetModuleHandleA(NULL);
    DWORD path_length;

    if (trace_file != INVALID_HANDLE_VALUE) {
        return TRUE;
    }
    path_length = GetEnvironmentVariableA(CHONK_BNE_TRACE_ENV, path, MAX_PATH);
    if (path_length == 0 || path_length >= MAX_PATH) {
        lstrcpynA(path, CHONK_BNE_TRACE_DEFAULT, MAX_PATH);
    }
    trace_file = CreateFileA(path, FILE_APPEND_DATA,
            FILE_SHARE_READ | FILE_SHARE_WRITE, NULL, OPEN_ALWAYS,
            FILE_ATTRIBUTE_NORMAL, NULL);
    if (trace_file == INVALID_HANDLE_VALUE) {
        return FALSE;
    }
    InitializeCriticalSection(&trace_lock);
    trace_lock_ready = TRUE;

    module_path[0] = '\0';
    target_path[0] = '\0';
    GetModuleFileNameA(tracer_module, module_path, MAX_PATH);
    GetModuleFileNameA(target, target_path, MAX_PATH);
    json_escape(module_path, escaped_module, sizeof(escaped_module));
    json_escape(target_path, escaped_target, sizeof(escaped_target));
    trace_write("# bne-trace protocol=%d event=attach pid=%lu "
            "target=\"%s\" tracer=\"%s\"",
            CHONK_BNE_TRACE_PROTOCOL, (unsigned long) GetCurrentProcessId(),
            escaped_target, escaped_module);
    return TRUE;
}

static void trace_close(void) {
    BOOL state_complete;

    if (trace_file == INVALID_HANDLE_VALUE) {
        return;
    }
    if (replay_schedule_requested) {
        trace_write("# bne-trace event=replay-schedule-closed complete=%s "
                "valid=%s consumed=%lu records=%lu",
                replay_schedule_valid
                        && replay_schedule_consumed == replay_schedule_records
                        ? "true" : "false",
                replay_schedule_valid ? "true" : "false",
                (unsigned long) replay_schedule_consumed,
                (unsigned long) replay_schedule_records);
    }
    trace_write("# bne-trace protocol=%d event=detach cycles=%ld screens=%ld",
            CHONK_BNE_TRACE_PROTOCOL, traced_cycles, screen_callbacks);
    state_complete = state_close();
    if (state_stream_requested) {
        trace_write("# bne-trace event=state-stream-closed complete=%s",
                state_complete ? "true" : "false");
    }
    FlushFileBuffers(trace_file);
    CloseHandle(trace_file);
    trace_file = INVALID_HANDLE_VALUE;
    if (trace_lock_ready) {
        DeleteCriticalSection(&trace_lock);
        trace_lock_ready = FALSE;
    }
    if (replay_schedule_data != NULL) {
        HeapFree(GetProcessHeap(), 0, replay_schedule_data);
        replay_schedule_data = NULL;
        replay_schedule_cursor = NULL;
        replay_schedule_end = NULL;
    }
}

static BOOL read_command_file(void) {
    char path[MAX_PATH];
    char line[512];
    DWORD length = GetEnvironmentVariableA(
            "CHONK_BNE_COMMANDS", path, sizeof(path));
    FILE *source;
    unsigned long line_number = 0;

    if (length == 0) {
        return TRUE;
    }
    if (length >= sizeof(path)) {
        trace_write("# bne-trace event=command-file-rejected reason=path-too-long");
        return FALSE;
    }
    source = fopen(path, "rb");
    if (source == NULL) {
        trace_write("# bne-trace event=command-file-rejected reason=open-failed");
        return FALSE;
    }
    while (fgets(line, sizeof(line), source) != NULL) {
        char *cursor = line;
        unsigned long cycle;
        unsigned long slot;
        unsigned long x;
        unsigned long y;
        char extra;
        int fields;

        line_number++;
        while (*cursor != '\0' && isspace((unsigned char) *cursor)) {
            cursor++;
        }
        if (*cursor == '\0' || *cursor == '#') {
            continue;
        }
        unsigned long target = SCRIPT_NO_TARGET;
        BYTE action = 0;

        extra = '\0';
        fields = sscanf(cursor,
                "cycle %lu move unit %lu x %lu y %lu %c",
                &cycle, &slot, &x, &y, &extra);
        if (fields == 4) {
            action = SCRIPT_COMMAND_MOVE;
        } else {
            extra = '\0';
            fields = sscanf(cursor,
                    "cycle %lu patrol unit %lu x %lu y %lu %c",
                    &cycle, &slot, &x, &y, &extra);
            if (fields == 4) {
                action = SCRIPT_COMMAND_PATROL;
            } else {
                extra = '\0';
                fields = sscanf(cursor,
                        "cycle %lu attack-ground unit %lu x %lu y %lu %c",
                        &cycle, &slot, &x, &y, &extra);
                if (fields == 4) {
                    /* Replay-pack-1 has 28 0x13 packets at function index
                     * 17, almost always dest xy and target -1. Constructor
                     * 0x004367a0 clears the unit target and installs
                     * order 17, or order 18 when that action is refused. */
                    action = SCRIPT_COMMAND_ATTACK_GROUND;
                } else {
                char action_name[16];

                extra = '\0';
                fields = sscanf(cursor,
                        "cycle %lu %15s unit %lu target %lu %c",
                        &cycle, action_name, &slot, &target, &extra);
                if (fields == 4 && strcmp(action_name, "attack") == 0) {
                    action = SCRIPT_COMMAND_ATTACK;
                    x = 0;
                    y = 0;
                } else if (fields == 4 && strcmp(action_name, "harvest") == 0) {
                    action = SCRIPT_COMMAND_HARVEST;
                    x = 0;
                    y = 0;
                } else if (fields == 4 && strcmp(action_name, "repair") == 0) {
                    /* Replay-pack-1 has 225 0x13 packets at function index
                     * 27, almost all with a live target. The constructor at
                     * 0x00436a20 installs order 27 when the target type
                     * flags carry 0x20 (building) or 0x0400 (transport),
                     * otherwise MOVE. The dispatcher does not special-case
                     * this index. */
                    action = SCRIPT_COMMAND_REPAIR;
                    x = 0;
                    y = 0;
                } else {
                    extra = '\0';
                    fields = sscanf(cursor, "cycle %lu %15s unit %lu %c",
                            &cycle, action_name, &slot, &extra);
                    if (fields == 3 && strcmp(action_name, "stop") == 0) {
                        /* Stop packets in the authenticated replay corpus carry
                         * dest 0,0 and no unit target. The 0x0C UI thunk is
                         * not this path. */
                        action = SCRIPT_COMMAND_STOP;
                        x = 0;
                        y = 0;
                        target = SCRIPT_NO_TARGET;
                    } else if (fields == 3
                            && strcmp(action_name, "return-goods") == 0) {
                        /* Replay-pack-1 has 382 0x13 packets with function
                         * index 24, dest 0,0 and target -1. Same shape as
                         * stop; the harvest 0x17 worker-flag test does not
                         * apply to this index. */
                        action = SCRIPT_COMMAND_RETURN_GOODS;
                        x = 0;
                        y = 0;
                        target = SCRIPT_NO_TARGET;
                    }
                }
            }
            }
        }
        if (action == 0 || cycle == 0 || cycle > 0x7fffffffUL
                || slot >= BNE_UNIT_LIMIT || x > 127 || y > 127
                || (target != SCRIPT_NO_TARGET && target >= BNE_UNIT_LIMIT)
                || script_command_count >= MAX_SCRIPT_COMMANDS
                || (script_command_count > 0
                    && cycle < (unsigned long) script_commands[
                            script_command_count - 1].cycle)) {
            trace_write("# bne-trace event=command-file-rejected "
                    "reason=invalid-command line=%lu", line_number);
            fclose(source);
            script_command_count = 0;
            return FALSE;
        }
        script_commands[script_command_count].cycle = (LONG) cycle;
        script_commands[script_command_count].unit_slot = (DWORD) slot;
        script_commands[script_command_count].target_slot = (DWORD) target;
        script_commands[script_command_count].action = action;
        script_commands[script_command_count].x = (BYTE) x;
        script_commands[script_command_count].y = (BYTE) y;
        script_command_count++;
    }
    if (ferror(source)) {
        trace_write("# bne-trace event=command-file-rejected reason=read-failed");
        fclose(source);
        script_command_count = 0;
        return FALSE;
    }
    fclose(source);
    trace_write("# bne-trace event=command-file-loaded commands=%lu",
            (unsigned long) script_command_count);
    return TRUE;
}

static WORD read_word(const BYTE *unit, size_t offset) {
    WORD value;
    memcpy(&value, unit + offset, sizeof(value));
    return value;
}

static void __cdecl traced_ai_home(BYTE *unit) {
    BYTE *pool = *BNE_202_UNIT_POOL_POINTER;
    WORD *components = *BNE_202_MAP_COMPONENTS_POINTER;
    WORD *squares = *BNE_202_MAP_SQUARES_POINTER;
    WORD map_size = *BNE_202_MAP_SIZE;
    DWORD slot = pool == NULL || unit < pool ? 0xffffffffUL
            : (DWORD) ((unit - pool) / BNE_UNIT_BYTES);
    BYTE *depot = ((find_unit_function) (void *)
            BNE_202_FIND_NEAREST_GOLD_DEPOT)(unit);
    WORD depot_x = depot == NULL ? 0xffffU : read_word(depot, BNE_UNIT_X);
    WORD depot_y = depot == NULL ? 0xffffU : read_word(depot, BNE_UNIT_Y);

    if ((LONG) slot == trace_unit_slot && depot_x > 0 && depot_y < map_size) {
        unsigned int owner = (unsigned int) unit[BNE_UNIT_OWNER];
        const BYTE *ai_state = owner < 8 ? BNE_202_AI_PLAYER_STATE
                + owner * BNE_202_AI_PLAYER_STATE_BYTES : NULL;
        trace_write("# bne-trace event=unit-ai-home-enter unit=%lu at=%u,%u "
                "depot-at=%u,%u depot-square=%04x west-square=%04x "
                "west-component=%d base-box=%u,%u,%u,%u",
                (unsigned long) slot,
                (unsigned int) read_word(unit, BNE_UNIT_X),
                (unsigned int) read_word(unit, BNE_UNIT_Y),
                (unsigned int) depot_x, (unsigned int) depot_y,
                squares == NULL ? 0xffffU
                        : (unsigned int) squares[depot_y * map_size + depot_x],
                squares == NULL ? 0xffffU : (unsigned int) squares[
                        depot_y * map_size + depot_x - 1],
                components == NULL ? -32768 : (int) (short) components[
                        depot_y * map_size + depot_x - 1],
                ai_state == NULL ? 0xffU : (unsigned int) ai_state[0x2b],
                ai_state == NULL ? 0xffU : (unsigned int) ai_state[0x2c],
                ai_state == NULL ? 0xffU : (unsigned int) ai_state[0x2d],
                ai_state == NULL ? 0xffU : (unsigned int) ai_state[0x2e]);
    }
    active_ai_home_slot = (LONG) slot;
    original_ai_home(unit);
    active_ai_home_slot = -1;
    if ((LONG) slot == trace_unit_slot) {
        trace_write("# bne-trace event=unit-ai-home-exit unit=%lu home=%u,%u "
                "behavior=%u marker=%u",
                (unsigned long) slot,
                (unsigned int) read_word(unit, BNE_UNIT_AI_HOME_X),
                (unsigned int) read_word(unit, BNE_UNIT_AI_HOME_Y),
                (unsigned int) unit[BNE_UNIT_AI_BEHAVIOR],
                (unsigned int) unit[BNE_UNIT_AI_MARKER]);
    }
}

static int __cdecl traced_set_ai_behavior(BYTE *unit, int behavior,
        DWORD *position) {
    BYTE *pool = *BNE_202_UNIT_POOL_POINTER;
    DWORD slot = pool == NULL || unit < pool ? 0xffffffffUL
            : (DWORD) ((unit - pool) / BNE_UNIT_BYTES);
    void *caller = __builtin_return_address(0);
    unsigned int before = unit == NULL ? 0xffU
            : (unsigned int) unit[BNE_UNIT_AI_BEHAVIOR];
    int result = original_set_ai_behavior(unit, behavior, position);

    if ((LONG) slot == trace_unit_slot) {
        trace_write("# bne-trace event=unit-ai-behavior caller=%p unit=%lu "
                "requested=%d before=%u after=%u position=%u,%u result=%d",
                caller, (unsigned long) slot, behavior, before,
                unit == NULL ? 0xffU
                        : (unsigned int) unit[BNE_UNIT_AI_BEHAVIOR],
                position == NULL ? 0xffffU
                        : (unsigned int) (position[0] & 0xffffU),
                position == NULL ? 0xffffU
                        : (unsigned int) (position[0] >> 16), result);
    }
    return result;
}

static int __cdecl traced_find_square(WORD *result, DWORD target, WORD type,
        DWORD required, BYTE radius, char same_component) {
    int found = original_find_square(
            result, target, type, required, radius, same_component);
    if (active_ai_home_slot == trace_unit_slot) {
        trace_write("# bne-trace event=unit-ai-home-search unit=%ld "
                "target=%u,%u type=%u required=%04lx radius=%u same=%d "
                "found=%d result=%u,%u",
                active_ai_home_slot, (unsigned int) (target & 0xffffU),
                (unsigned int) (target >> 16), (unsigned int) type,
                (unsigned long) required, (unsigned int) radius,
                (int) same_component, found,
                (unsigned int) result[0], (unsigned int) result[1]);
    }
    return found;
}

static int __cdecl traced_check_build_site(BYTE *unit, WORD x, WORD y,
        DWORD type) {
    int result = original_check_build_site(unit, x, y, type);
    BYTE *pool = *BNE_202_UNIT_POOL_POINTER;
    if (trace_unit_slot >= 0 && pool != NULL
            && unit == pool + (DWORD) trace_unit_slot * BNE_UNIT_BYTES) {
        WORD map_size = *BNE_202_MAP_SIZE;
        WORD *squares = *BNE_202_MAP_SQUARES_POINTER;
        unsigned int square = squares != NULL && x < map_size && y < map_size
                ? squares[y * map_size + x] : 0xffffU;
        unsigned int probe = squares != NULL && x + 2 < map_size
                && y + 2 < map_size
                ? squares[(y + 2) * map_size + x + 2] : 0xffffU;
        unsigned int far_probe = squares != NULL && x + 3 < map_size
                && y + 3 < map_size
                ? squares[(y + 3) * map_size + x + 3] : 0xffffU;
        trace_write("# bne-trace event=unit-build-candidate unit=%ld "
                "type=%u x=%u y=%u result=%u square=%04x "
                "plus2=%04x plus3=%04x", trace_unit_slot,
                (unsigned int) (type & 0xffU), (unsigned int) x,
                (unsigned int) y, (unsigned int) (result & 0xffff),
                square, probe, far_probe);
    }
    return result;
}

static void __cdecl traced_set_no_build(WORD *position, DWORD span) {
    void *caller = __builtin_return_address(0);
    WORD x = position == NULL ? 0xffffU : position[0];
    WORD y = position == NULL ? 0xffffU : position[1];
    original_set_no_build(position, span);
    trace_write("# bne-trace event=no-build-set caller=%p x=%u y=%u span=%u",
            caller, (unsigned int) x, (unsigned int) y,
            (unsigned int) (span & 0xffU));
}

static void __cdecl traced_clear_no_build(WORD *position, DWORD type) {
    void *caller = __builtin_return_address(0);
    WORD x = position == NULL ? 0xffffU : position[0];
    WORD y = position == NULL ? 0xffffU : position[1];
    original_clear_no_build(position, type);
    trace_write("# bne-trace event=no-build-clear caller=%p x=%u y=%u type=%u",
            caller, (unsigned int) x, (unsigned int) y,
            (unsigned int) (type & 0xffU));
}

static int __cdecl traced_find_ai_wood(BYTE *unit, WORD *position) {
    BYTE *pool = *BNE_202_UNIT_POOL_POINTER;
    DWORD slot = pool == NULL || unit < pool ? 0xffffffffUL
            : (DWORD) ((unit - pool) / BNE_UNIT_BYTES);
    int found = original_find_ai_wood(unit, position);
    if ((LONG) slot == trace_unit_slot) {
        WORD map_size = *BNE_202_MAP_SIZE;
        WORD *components = *BNE_202_MAP_COMPONENTS_POINTER;
        WORD *squares = *BNE_202_MAP_SQUARES_POINTER;
        unsigned int index = position[1] * map_size + position[0];
        trace_write("# bne-trace event=unit-find-ai-wood unit=%lu "
                "found=%d result=%u,%u component=%d square=%04x",
                (unsigned long) slot, found,
                (unsigned int) position[0], (unsigned int) position[1],
                components == NULL || position[0] >= map_size
                        || position[1] >= map_size ? -32768
                        : (int) (short) components[index],
                squares == NULL || position[0] >= map_size
                        || position[1] >= map_size ? 0xffffU
                        : (unsigned int) squares[index]);
    }
    return found;
}

static void trace_selected_unit_components(LONG cycle, const BYTE *pool,
        DWORD pool_count) {
    const BYTE *unit;
    const BYTE *target;
    const BYTE *depot;
    const BYTE *hostile;
    const BYTE *auto_target;
    const BYTE *ai_state;
    find_unit_function find_depot =
            (find_unit_function) (void *) BNE_202_FIND_NEAREST_GOLD_DEPOT;
    find_unit_function find_hostile =
            (find_unit_function) (void *) BNE_202_FIND_NEAREST_HOSTILE;
    WORD *components = *BNE_202_MAP_COMPONENTS_POINTER;
    WORD map_size = *BNE_202_MAP_SIZE;
    WORD unit_x;
    WORD unit_y;
    WORD target_x;
    WORD target_y;
    WORD *squares = *BNE_202_MAP_SQUARES_POINTER;
    unsigned int target_width;
    unsigned int target_height;
    DWORD target_slot;
    DWORD depot_slot = 0xffffffffUL;
    DWORD hostile_slot = 0xffffffffUL;
    DWORD auto_target_slot = 0xffffffffUL;
    int hostile_score = 0;
    unsigned int owner;

    if (trace_unit_slot < 0 || pool == NULL
            || (DWORD) trace_unit_slot >= pool_count || components == NULL
            || map_size == 0 || map_size > BNE_MAP_LIMIT) {
        return;
    }
    unit = pool + (DWORD) trace_unit_slot * BNE_UNIT_BYTES;

    {
        char route[3 * 20 + 1];
        size_t used = 0;
        unsigned int index;
        route[0] = '\0';
        for (index = 0; index < 20; index++) {
            int written = snprintf(route + used, sizeof(route) - used,
                    "%s%02x", index == 0 ? "" : ",",
                    (unsigned int) unit[BNE_UNIT_ROUTE + index]);
            if (written < 0 || (size_t) written >= sizeof(route) - used) {
                break;
            }
            used += (size_t) written;
        }
        trace_write("# bne-trace event=unit-route cycle=%ld unit=%ld "
                "index=%u bytes=%s", cycle, trace_unit_slot,
                (unsigned int) unit[BNE_UNIT_ROUTE_INDEX], route);
    }

    if (squares != NULL) {
        WORD order_x = read_word(unit, BNE_UNIT_ORDER_X);
        WORD order_y = read_word(unit, BNE_UNIT_ORDER_Y);
        int min_x = order_x > 2 ? (int) order_x - 2 : 0;
        int min_y = order_y > 2 ? (int) order_y - 2 : 0;
        int max_x = order_x + 2 < map_size ? (int) order_x + 2
                : (int) map_size - 1;
        int max_y = order_y + 2 < map_size ? (int) order_y + 2
                : (int) map_size - 1;
        int y;
        for (y = min_y; y <= max_y; y++) {
            char row[5 * 5 + 1];
            size_t used = 0;
            int x;
            row[0] = '\0';
            for (x = min_x; x <= max_x; x++) {
                int written = snprintf(row + used, sizeof(row) - used,
                        "%s%04x", x == min_x ? "" : ",",
                        (unsigned int) squares[y * map_size + x]);
                if (written < 0
                        || (size_t) written >= sizeof(row) - used) {
                    break;
                }
                used += (size_t) written;
            }
            trace_write("# bne-trace event=unit-map-squares cycle=%ld "
                    "unit=%ld y=%d x=%d..%d values=%s", cycle,
                    trace_unit_slot, y, min_x, max_x, row);
            if (components != NULL) {
                char component_row[7 * 7 + 1];
                size_t component_used = 0;
                int component_x;
                component_row[0] = '\0';
                for (component_x = min_x; component_x <= max_x;
                        component_x++) {
                    int written = snprintf(component_row + component_used,
                            sizeof(component_row) - component_used,
                            "%s%04x", component_x == min_x ? "" : ",",
                            (unsigned int) components[
                                y * map_size + component_x]);
                    if (written < 0 || (size_t) written
                            >= sizeof(component_row) - component_used) {
                        break;
                    }
                    component_used += (size_t) written;
                }
                trace_write("# bne-trace event=unit-map-components cycle=%ld "
                        "unit=%ld y=%d x=%d..%d values=%s", cycle,
                        trace_unit_slot, y, min_x, max_x, component_row);
            }
        }
    }

    owner = (unsigned int) unit[BNE_UNIT_OWNER];
    ai_state = owner < 8 ? BNE_202_AI_PLAYER_STATE
            + owner * BNE_202_AI_PLAYER_STATE_BYTES : NULL;
    depot = find_depot((BYTE *) unit);
    hostile = find_hostile((BYTE *) unit);
    auto_target = ((find_auto_target_function) (void *)
            BNE_202_FIND_AUTO_TARGET)((BYTE *) unit, '\0');
    if (hostile != NULL) {
        hostile_score = ((target_score_function) (void *)
                BNE_202_TARGET_SCORE)((BYTE *) unit, (BYTE *) hostile);
    }
    if (depot != NULL && depot >= pool
            && depot < pool + pool_count * BNE_UNIT_BYTES
            && (DWORD) (depot - pool) % BNE_UNIT_BYTES == 0) {
        depot_slot = (DWORD) ((depot - pool) / BNE_UNIT_BYTES);
    }
    if (hostile != NULL && hostile >= pool
            && hostile < pool + pool_count * BNE_UNIT_BYTES
            && (DWORD) (hostile - pool) % BNE_UNIT_BYTES == 0) {
        hostile_slot = (DWORD) ((hostile - pool) / BNE_UNIT_BYTES);
    }
    if (auto_target != NULL && auto_target >= pool
            && auto_target < pool + pool_count * BNE_UNIT_BYTES
            && (DWORD) (auto_target - pool) % BNE_UNIT_BYTES == 0) {
        auto_target_slot = (DWORD) ((auto_target - pool) / BNE_UNIT_BYTES);
    }
    unit_x = read_word(unit, BNE_UNIT_X);
    unit_y = read_word(unit, BNE_UNIT_Y);
    trace_write("# bne-trace event=unit-type-data cycle=%ld unit=%ld "
            "type=%u react-computer=%u react-person=%u sight=%u "
            "target-mask=%02x priority=%u type-flags=%08lx",
            cycle, trace_unit_slot, (unsigned int) unit[BNE_UNIT_TYPE],
            (unsigned int) BNE_202_UNIT_REACT_COMPUTER[unit[BNE_UNIT_TYPE]],
            (unsigned int) BNE_202_UNIT_REACT_PERSON[unit[BNE_UNIT_TYPE]],
            (unsigned int) BNE_202_UNIT_SIGHT_RANGE[unit[BNE_UNIT_TYPE]],
            (unsigned int) BNE_202_UNIT_TARGET_MASK[unit[BNE_UNIT_TYPE]],
            (unsigned int) BNE_202_UNIT_PRIORITY[unit[BNE_UNIT_TYPE]],
            (unsigned long) BNE_202_UNIT_TYPE_FLAGS[unit[BNE_UNIT_TYPE]]);
    trace_write("# bne-trace event=unit-auto-target cycle=%ld unit=%ld "
            "nearest=%lu nearest-score=%d selected=%lu nearest-size=%u,%u "
            "nearest-flags=%08lx",
            cycle, trace_unit_slot, (unsigned long) hostile_slot,
            hostile_score, (unsigned long) auto_target_slot,
            hostile == NULL ? 0xffffU : (unsigned int)
                    BNE_202_UNIT_TILE_WIDTH[hostile[BNE_UNIT_TYPE] * 2],
            hostile == NULL ? 0xffffU : (unsigned int)
                    BNE_202_UNIT_TILE_HEIGHT[hostile[BNE_UNIT_TYPE] * 2],
            hostile == NULL ? 0xffffffffUL : (unsigned long)
                    BNE_202_UNIT_TYPE_FLAGS[hostile[BNE_UNIT_TYPE]]);
    trace_write("# bne-trace event=unit-ai-home cycle=%ld unit=%ld "
            "owner=%u home=%u,%u behavior=%u marker=%u depot=%lu "
            "depot-at=%u,%u hostile=%lu hostile-at=%u,%u "
            "base-box=%u,%u,%u,%u depot-west-component=%d "
            "depot-west-square=%04x home-component=%d home-square=%04x",
            cycle, trace_unit_slot, owner,
            (unsigned int) read_word(unit, BNE_UNIT_AI_HOME_X),
            (unsigned int) read_word(unit, BNE_UNIT_AI_HOME_Y),
            (unsigned int) unit[BNE_UNIT_AI_BEHAVIOR],
            (unsigned int) unit[BNE_UNIT_AI_MARKER],
            (unsigned long) depot_slot,
            depot_slot == 0xffffffffUL ? 0xffffU
                    : (unsigned int) read_word(depot, BNE_UNIT_X),
            depot_slot == 0xffffffffUL ? 0xffffU
                    : (unsigned int) read_word(depot, BNE_UNIT_Y),
            (unsigned long) hostile_slot,
            hostile_slot == 0xffffffffUL ? 0xffffU
                    : (unsigned int) read_word(hostile, BNE_UNIT_X),
            hostile_slot == 0xffffffffUL ? 0xffffU
                    : (unsigned int) read_word(hostile, BNE_UNIT_Y),
            ai_state == NULL ? 0xffU : (unsigned int) ai_state[0x2b],
            ai_state == NULL ? 0xffU : (unsigned int) ai_state[0x2c],
            ai_state == NULL ? 0xffU : (unsigned int) ai_state[0x2d],
            ai_state == NULL ? 0xffU : (unsigned int) ai_state[0x2e],
            depot_slot == 0xffffffffUL || read_word(depot, BNE_UNIT_X) == 0
                    ? -32768 : (int) (short) components[
                        read_word(depot, BNE_UNIT_Y) * map_size
                        + read_word(depot, BNE_UNIT_X) - 1],
            depot_slot == 0xffffffffUL || read_word(depot, BNE_UNIT_X) == 0
                    || squares == NULL ? 0xffffU : (unsigned int) squares[
                        read_word(depot, BNE_UNIT_Y) * map_size
                        + read_word(depot, BNE_UNIT_X) - 1],
            read_word(unit, BNE_UNIT_AI_HOME_X) >= map_size
                    || read_word(unit, BNE_UNIT_AI_HOME_Y) >= map_size
                    ? -32768 : (int) (short) components[
                        read_word(unit, BNE_UNIT_AI_HOME_Y) * map_size
                        + read_word(unit, BNE_UNIT_AI_HOME_X)],
            squares == NULL || read_word(unit, BNE_UNIT_AI_HOME_X) >= map_size
                    || read_word(unit, BNE_UNIT_AI_HOME_Y) >= map_size
                    ? 0xffffU : (unsigned int) squares[
                        read_word(unit, BNE_UNIT_AI_HOME_Y) * map_size
                        + read_word(unit, BNE_UNIT_AI_HOME_X)]);
    memcpy(&target, unit + BNE_UNIT_TARGET, sizeof(target));
    if (target == NULL || target < pool
            || target >= pool + pool_count * BNE_UNIT_BYTES
            || (DWORD) (target - pool) % BNE_UNIT_BYTES != 0) {
        unsigned int unit_width = (unsigned int)
                ((WORD *) 0x004cee6c)[(unsigned int) unit[BNE_UNIT_TYPE] * 2];
        unsigned int unit_height = (unsigned int)
                ((WORD *) 0x004cee6e)[(unsigned int) unit[BNE_UNIT_TYPE] * 2];
        unsigned int right = unit_width == 0 ? unit_x
                : unit_x + unit_width - 1;
        unsigned int bottom = unit_height == 0 ? unit_y
                : unit_y + unit_height - 1;
        trace_write("# bne-trace event=unit-components cycle=%ld unit=%ld "
                "x=%u y=%u component=%d type-flags=%08lx self-size=%ux%u "
                "self-corners-br=%d,tr=%d,bl=%d,tl=%d "
                "target=none order-x=%u order-y=%u",
                cycle, trace_unit_slot, (unsigned int) unit_x,
                (unsigned int) unit_y,
                (int) (short) components[unit_y * map_size + unit_x],
                (unsigned long) ((DWORD *) 0x004cf574)[
                        (unsigned int) unit[BNE_UNIT_TYPE]],
                unit_width, unit_height,
                right >= map_size || bottom >= map_size ? -32768
                        : (int) (short) components[bottom * map_size + right],
                right >= map_size ? -32768
                        : (int) (short) components[unit_y * map_size + right],
                bottom >= map_size ? -32768
                        : (int) (short) components[bottom * map_size + unit_x],
                (int) (short) components[unit_y * map_size + unit_x],
                (unsigned int) read_word(unit, BNE_UNIT_ORDER_X),
                (unsigned int) read_word(unit, BNE_UNIT_ORDER_Y));
        return;
    }
    target_slot = (DWORD) ((target - pool) / BNE_UNIT_BYTES);
    target_x = read_word(target, BNE_UNIT_X);
    target_y = read_word(target, BNE_UNIT_Y);
    target_width = (unsigned int)
            ((WORD *) 0x004cee6c)[(unsigned int) target[BNE_UNIT_TYPE] * 2];
    target_height = (unsigned int)
            ((WORD *) 0x004cee6e)[(unsigned int) target[BNE_UNIT_TYPE] * 2];
    if (target_width == 0 || target_height == 0
            || target_x + target_width > map_size
            || target_y + target_height > map_size) {
        return;
    }
    trace_write("# bne-trace event=unit-components cycle=%ld unit=%ld "
            "x=%u y=%u component=%d target=%lu target-type=%u "
            "target-x=%u target-y=%u target-size=%ux%u "
            "corners-br=%d,tr=%d,bl=%d,tl=%d order-x=%u order-y=%u",
            cycle, trace_unit_slot, (unsigned int) unit_x,
            (unsigned int) unit_y,
            (int) (short) components[unit_y * map_size + unit_x],
            (unsigned long) target_slot, (unsigned int) target[BNE_UNIT_TYPE],
            (unsigned int) target_x, (unsigned int) target_y,
            target_width, target_height,
            (int) (short) components[(target_y + target_height - 1) * map_size
                    + target_x + target_width - 1],
            (int) (short) components[target_y * map_size
                    + target_x + target_width - 1],
            (int) (short) components[(target_y + target_height - 1) * map_size
                    + target_x],
            (int) (short) components[target_y * map_size + target_x],
            (unsigned int) read_word(unit, BNE_UNIT_ORDER_X),
            (unsigned int) read_word(unit, BNE_UNIT_ORDER_Y));
}

static const char *script_action_name(BYTE action) {
    if (action == SCRIPT_COMMAND_MOVE) {
        return "move";
    }
    if (action == SCRIPT_COMMAND_STOP) {
        return "stop";
    }
    if (action == SCRIPT_COMMAND_ATTACK) {
        return "attack";
    }
    if (action == SCRIPT_COMMAND_HARVEST) {
        return "harvest";
    }
    if (action == SCRIPT_COMMAND_PATROL) {
        return "patrol";
    }
    if (action == SCRIPT_COMMAND_RETURN_GOODS) {
        return "return-goods";
    }
    if (action == SCRIPT_COMMAND_REPAIR) {
        return "repair";
    }
    if (action == SCRIPT_COMMAND_ATTACK_GROUND) {
        return "attack-ground";
    }
    return "unknown";
}

static unsigned int script_order_function_index(BYTE action) {
    /* These indices are the 0x13 packet's function byte as executed by
     * 0x00475f80, which loads ORDER_FUNCTIONS[packet[7]] and calls
     * GiveOrder at 0x00451070 / 0x0047617f. Replay-pack-1 counts:
     * table[3] move, table[2] stop (88 packets, dest 0,0, target -1),
     * table[8] attack (221 packets with a live target), table[23]
     * harvest (dispatcher special-cases 0x17 as the worker flag test),
     * table[24] return-goods (382 packets, dest 0,0, target -1),
     * table[27] repair (225 packets, live building or transport target).
     * The one-byte 0x0C thunk at 0x00436ee0 is UI/speech and is unused. */
    if (action == SCRIPT_COMMAND_MOVE) {
        return 3;
    }
    if (action == SCRIPT_COMMAND_STOP) {
        return 2;
    }
    if (action == SCRIPT_COMMAND_ATTACK) {
        return 8;
    }
    if (action == SCRIPT_COMMAND_HARVEST) {
        return 23;
    }
    if (action == SCRIPT_COMMAND_PATROL) {
        return 5;
    }
    if (action == SCRIPT_COMMAND_RETURN_GOODS) {
        return 24;
    }
    if (action == SCRIPT_COMMAND_REPAIR) {
        return 27;
    }
    if (action == SCRIPT_COMMAND_ATTACK_GROUND) {
        return 17;
    }
    return 0xff;
}

static void reject_command(const script_command *command, const char *reason) {
    trace_write("# bne-trace event=command-rejected cycle=%ld action=%s "
            "unit=%lu x=%u y=%u reason=%s", command->cycle,
            script_action_name(command->action),
            (unsigned long) command->unit_slot,
            (unsigned int) command->x, (unsigned int) command->y, reason);
}

static void apply_commands(LONG cycle) {
    static const BYTE expected_give_order[] = {
        0x8b, 0x44, 0x24, 0x04, 0x33, 0xc9
    };
    BYTE *pool = *BNE_202_UNIT_POOL_POINTER;
    DWORD pool_count = *BNE_202_UNIT_POOL_COUNT;

    while (next_script_command < script_command_count
            && script_commands[next_script_command].cycle <= cycle) {
        const script_command *command =
                &script_commands[next_script_command++];
        BYTE *unit;
        BYTE *target = NULL;
        void *order_function;
        unsigned int function_index;
        unsigned int dest_x = command->x;
        unsigned int dest_y = command->y;

        if (command->cycle < cycle) {
            reject_command(command, "missed-cycle");
            continue;
        }
        if (pool == NULL || command->unit_slot >= pool_count) {
            reject_command(command, "unit-slot-out-of-range");
            continue;
        }
        unit = pool + command->unit_slot * BNE_UNIT_BYTES;
        if ((unit[BNE_UNIT_FLAGS3] & (BNE_UNIT_FREE | BNE_UNIT_DEAD)) != 0) {
            reject_command(command, "unit-not-live");
            continue;
        }
        if (unit[BNE_UNIT_OWNER] != *BNE_202_LOCAL_PLAYER) {
            reject_command(command, "unit-not-local");
            continue;
        }
        if (command->action == SCRIPT_COMMAND_HARVEST
                && (BNE_202_UNIT_TYPE_FLAGS[unit[BNE_UNIT_TYPE]]
                    & SCRIPT_WORKER_TYPE_FLAGS) == 0) {
            /* 0x00475f80 special-cases index 0x17 with this same mask.
             * A grunt must not become a harvester. */
            reject_command(command, "not-a-worker");
            continue;
        }
        if (command->target_slot != SCRIPT_NO_TARGET) {
            if (command->target_slot >= pool_count) {
                reject_command(command, "target-slot-out-of-range");
                continue;
            }
            target = pool + command->target_slot * BNE_UNIT_BYTES;
            if ((target[BNE_UNIT_FLAGS3]
                    & (BNE_UNIT_FREE | BNE_UNIT_DEAD)) != 0) {
                reject_command(command, "target-not-live");
                continue;
            }
            if (target == unit) {
                reject_command(command, "target-is-self");
                continue;
            }
            dest_x = read_word(target, BNE_UNIT_X);
            dest_y = read_word(target, BNE_UNIT_Y);
        } else if (command->action == SCRIPT_COMMAND_ATTACK
                || command->action == SCRIPT_COMMAND_HARVEST
                || command->action == SCRIPT_COMMAND_REPAIR) {
            reject_command(command, "target-required");
            continue;
        }
        if (!executable_page_contains(BNE_202_GIVE_ORDER)
                || memcmp(BNE_202_GIVE_ORDER, expected_give_order,
                    sizeof(expected_give_order)) != 0) {
            reject_command(command, "give-order-signature");
            continue;
        }
        function_index = script_order_function_index(command->action);
        if (function_index > 60) {
            reject_command(command, "unsupported-action");
            continue;
        }
        order_function = BNE_202_ORDER_FUNCTIONS[function_index];
        if (!executable_page_contains(order_function)) {
            reject_command(command, "order-function");
            continue;
        }
        ((give_order_function) (void *) BNE_202_GIVE_ORDER)(
                unit, (int) dest_x, (int) dest_y, target, order_function);
        trace_write("# bne-trace event=command-applied cycle=%ld action=%s "
                "unit=%lu target=%lu x=%u y=%u function-index=%u",
                command->cycle, script_action_name(command->action),
                (unsigned long) command->unit_slot,
                (unsigned long) command->target_slot,
                dest_x, dest_y, function_index);
    }
}

static void trace_command_unit_state(LONG cycle, const BYTE *pool,
        DWORD pool_count) {
    DWORD command_index;

    if (pool == NULL) {
        return;
    }
    for (command_index = 0; command_index < script_command_count;
            command_index++) {
        const script_command *command = &script_commands[command_index];
        const BYTE *unit;
        DWORD previous;
        BOOL duplicate = FALSE;

        for (previous = 0; previous < command_index; previous++) {
            if (script_commands[previous].unit_slot == command->unit_slot) {
                duplicate = TRUE;
                break;
            }
        }
        if (duplicate || command->unit_slot >= pool_count) {
            continue;
        }
        unit = pool + command->unit_slot * BNE_UNIT_BYTES;
        trace_write("# bne-trace event=command-unit-state cycle=%ld unit=%lu "
                "sequence=%u sequence-flags=%u animation-timer=%u "
                "animation=%u frame=%u face=%u order=%u next-order=%u "
                "order-x=%u order-y=%u path-head=%u",
                cycle, (unsigned long) command->unit_slot,
                (unsigned int) read_word(unit, BNE_UNIT_SEQUENCE),
                (unsigned int) unit[BNE_UNIT_SEQUENCE_FLAGS],
                (unsigned int) unit[BNE_UNIT_ANIMATION_TIMER],
                (unsigned int) unit[BNE_UNIT_ANIMATION],
                (unsigned int) unit[BNE_UNIT_FRAME],
                (unsigned int) unit[BNE_UNIT_FACE],
                (unsigned int) unit[BNE_UNIT_ORDER],
                (unsigned int) unit[BNE_UNIT_NEXT_ORDER],
                (unsigned int) read_word(unit, BNE_UNIT_ORDER_X),
                (unsigned int) read_word(unit, BNE_UNIT_ORDER_Y),
                (unsigned int) unit[BNE_UNIT_MOVEMENT_PATH]);
    }
}

static int __cdecl traced_load_scenario(int scenario_file, int scenario_size) {
    int result;
    BYTE *pool;
    DWORD pool_count;

    /* BNE's PUD unit constructor consumes this independent RNG for initial
     * facing and animation delay. Pin it immediately before the loader so no
     * earlier startup/UI random calls can perturb scenario construction. */
    if (deterministic_seed_enabled) {
        *BNE_202_ASYNC_RANDOM_SEED = deterministic_seed;
        trace_write("# bne-trace event=initialization-seed-applied seed=%lu",
                (unsigned long) deterministic_seed);
    }
    result = original_load_scenario(scenario_file, scenario_size);
    trace_sync_random_calls = TRUE;
    trace_async_random_calls = TRUE;
    pool = *BNE_202_UNIT_POOL_POINTER;
    pool_count = *BNE_202_UNIT_POOL_COUNT;

    /* The populated pool is the stable success boundary.  Do not depend on
     * this old loader's undocumented integer return convention. */
    replay_game_ready = pool != NULL && pool_count != 0;

    trace_write("# bne-trace event=scenario-loaded result=%d slots=%lu "
            "async-seed=%lu", result, (unsigned long) pool_count,
            (unsigned long) *BNE_202_ASYNC_RANDOM_SEED);
    if (replay_schedule_valid) {
        trace_write("# bne-trace event=replay-game-boundary ready=%s "
                "records=%lu", replay_game_ready ? "true" : "false",
                (unsigned long) replay_schedule_records);
    }
    trace_critter_scheduler_state(pool, pool_count);
    trace_initialization_semantics("scenario", 0, pool, pool_count);
    trace_command_unit_state(0, pool, pool_count);
    return result;
}

/* Reproduce BNE 2.02b's synchronized gameplay RNG exactly. The five-byte
 * entry hook below is guarded by the retail routine's original signature, so
 * an unknown executable is rejected rather than patched. During the active
 * unit pass, traced_cycles still names the preceding snapshot; the event
 * therefore belongs to traced_cycles + 1. */
static int __cdecl traced_sync_random(void) {
    DWORD before = *BNE_202_RANDOM_SEED;
    DWORD after = before * 0x41c64e6dUL + 0x3039UL;
    int result = (int) ((after >> 16) & 0x7fffUL);
    LONG cycle = match_ready ? traced_cycles + 1 : 0;

    *BNE_202_RANDOM_SEED = after;
    if (trace_sync_random_calls) {
        trace_write("# bne-trace event=sync-random cycle=%ld caller=%p "
                "before=%lu after=%lu result=%d", cycle,
                __builtin_return_address(0), (unsigned long) before,
                (unsigned long) after, result);
    }
    return result;
}

/* BNE keeps cosmetic/unit-construction randomness separate from the synced
 * gameplay seed. Reproduce this 18-byte function exactly so its callers can
 * be identified without perturbing the random sequence. */
static int __cdecl traced_async_random(void) {
    DWORD before = *BNE_202_ASYNC_RANDOM_SEED;
    DWORD after = before * 0x015a4e35UL + 1;
    int result = (int) ((after >> 16) & 0x7fffUL);
    /* Same cycle accounting as traced_sync_random: during the unit pass,
     * traced_cycles still names the preceding snapshot. */
    LONG cycle = match_ready ? traced_cycles + 1 : 0;

    *BNE_202_ASYNC_RANDOM_SEED = after;
    if (trace_async_random_calls) {
        trace_write("# bne-trace event=async-random cycle=%ld caller=%p "
                "before=%lu after=%lu result=%d", cycle,
                __builtin_return_address(0),
                (unsigned long) before, (unsigned long) after, result);
    }
    return result;
}

static void __cdecl traced_idle(BYTE *unit) {
    BYTE *pool = *BNE_202_UNIT_POOL_POINTER;
    DWORD slot = (pool == NULL || unit < pool)
            ? 0xffffffffUL : (DWORD) ((unit - pool) / BNE_UNIT_BYTES);
    DWORD seed = *BNE_202_ASYNC_RANDOM_SEED;
    WORD before_x = read_word(unit, BNE_UNIT_X);
    WORD before_y = read_word(unit, BNE_UNIT_Y);

    original_idle(unit);
    if (trace_async_random_calls) {
        trace_write("# bne-trace event=idle-dispatch unit=%lu type=%u "
                "seed-before=%lu seed-after=%lu timer=%u sequence=%u "
                "sequence-flags=%u order=%u before=%u,%u after=%u,%u",
                (unsigned long) slot, (unsigned int) unit[BNE_UNIT_TYPE],
                (unsigned long) seed,
                (unsigned long) *BNE_202_ASYNC_RANDOM_SEED,
                (unsigned int) unit[BNE_UNIT_ANIMATION_TIMER],
                (unsigned int) read_word(unit, BNE_UNIT_SEQUENCE),
                (unsigned int) unit[BNE_UNIT_SEQUENCE_FLAGS],
                (unsigned int) unit[BNE_UNIT_ORDER],
                (unsigned int) before_x, (unsigned int) before_y,
                (unsigned int) read_word(unit, BNE_UNIT_X),
                (unsigned int) read_word(unit, BNE_UNIT_Y));
    }
}

static BYTE *__cdecl traced_projectile(BYTE *unit) {
    BYTE *pool = *BNE_202_UNIT_POOL_POINTER;
    DWORD slot = (pool == NULL || unit == NULL || unit < pool)
            ? 0xffffffffUL : (DWORD) ((unit - pool) / BNE_UNIT_BYTES);
    DWORD seed = *BNE_202_ASYNC_RANDOM_SEED;
    WORD sequence = unit == NULL ? 0 : read_word(unit, BNE_UNIT_SEQUENCE);
    BYTE *result = original_projectile(unit);

    if (trace_async_random_calls) {
        trace_write("# bne-trace event=projectile-created unit=%lu type=%u "
                "animation=%u timer=%u sequence=%u sequence-flags=%u "
                "seed-before=%lu seed-after=%lu result=%p",
                (unsigned long) slot,
                (unsigned int) (unit == NULL ? 0xff : unit[BNE_UNIT_TYPE]),
                (unsigned int) (unit == NULL ? 0xff
                        : unit[BNE_UNIT_ANIMATION]),
                (unsigned int) (unit == NULL ? 0xff
                        : unit[BNE_UNIT_ANIMATION_TIMER]),
                (unsigned int) sequence,
                (unsigned int) (unit == NULL ? 0xff
                        : unit[BNE_UNIT_SEQUENCE_FLAGS]),
                (unsigned long) seed,
                (unsigned long) *BNE_202_ASYNC_RANDOM_SEED, result);
    }
    return result;
}

static void __cdecl traced_internal_give_order(BYTE *unit, int x, int y,
        BYTE *target, void *order_function) {
    BYTE *pool = *BNE_202_UNIT_POOL_POINTER;
    DWORD slot = (pool == NULL || unit < pool)
            ? 0xffffffffUL : (DWORD) ((unit - pool) / BNE_UNIT_BYTES);
    DWORD target_slot = (pool == NULL || target == NULL || target < pool)
            ? 0xffffffffUL : (DWORD) ((target - pool) / BNE_UNIT_BYTES);
    unsigned int before = unit == NULL ? 0xff : unit[BNE_UNIT_ORDER];
    unsigned int before_next = unit == NULL ? 0xff : unit[BNE_UNIT_NEXT_ORDER];
    unsigned int function_index = 0xff;
    unsigned int index;

    for (index = 0; index <= 60; index++) {
        if (BNE_202_ORDER_FUNCTIONS[index] == order_function) {
            function_index = index;
            break;
        }
    }
    original_internal_give_order(unit, x, y, target, order_function);
    trace_write("# bne-trace event=internal-order caller=%p unit=%lu "
            "type=%u before=%u after=%u next-before=%u next-after=%u "
            "function-index=%u "
            "target=%lu x=%d y=%d",
            __builtin_return_address(0), (unsigned long) slot,
            unit == NULL ? 0xff : (unsigned int) unit[BNE_UNIT_TYPE],
            before, unit == NULL ? 0xff : (unsigned int) unit[BNE_UNIT_ORDER],
            before_next,
            unit == NULL ? 0xff : (unsigned int) unit[BNE_UNIT_NEXT_ORDER],
            function_index, (unsigned long) target_slot, x, y);
}

static void __cdecl traced_master_seed(DWORD observed_seed) {
    DWORD applied_seed = deterministic_seed_enabled
            ? deterministic_seed : observed_seed;

    original_master_seed(applied_seed);
    trace_write("# bne-trace event=master-seed-call "
            "observed=%lu applied=%lu deterministic=%s",
            (unsigned long) observed_seed, (unsigned long) applied_seed,
            deterministic_seed_enabled ? "true" : "false");
}

static void snapshot_cycle(void) {
    BYTE *pool = *BNE_202_UNIT_POOL_POINTER;
    DWORD pool_count = *BNE_202_UNIT_POOL_COUNT;
    LONG cycle = InterlockedIncrement(&traced_cycles);
    int player;
    DWORD index;

    trace_write("cycle %ld seed %08lx", cycle,
            (unsigned long) *BNE_202_RANDOM_SEED);
    trace_write("# bne-trace event=async-seed cycle=%ld seed=%lu",
            cycle, (unsigned long) *BNE_202_ASYNC_RANDOM_SEED);
    /* The warmup and first game-before dumps prove initialisation only.  The
       per-cycle after-state is the actual AI decision oracle: it lets the
       normalizer diff the 48-byte state at the same committed simulation
       boundary as units, banks, commands, and projectiles. */
    trace_ai_build_boundaries("game-after", cycle);
    if (cycle == 1) {
        /* The per-type flag word decides which behaviour the computer's
           assigner hands a unit, and it lives in .bss, so it cannot be read
           out of the executable. Answering one question about it used to cost
           one capture per unit type. Dump the whole table once, on the first
           traced cycle, by which point the scenario has filled it in. */
        DWORD type_index;
        for (type_index = 0; type_index < 112; type_index++) {
            trace_write("# bne-trace event=unit-type-flags type=%lu "
                    "flags=%08lx",
                    (unsigned long) type_index,
                    (unsigned long) BNE_202_UNIT_TYPE_FLAGS[type_index]);
        }
        /* The scorer's priority byte, from the same .bss the flags live in.
           Which of two enemies a fighter walks to is this number less a
           quarter of the square of the distance, and a single point of it
           decides whether XHuman 12's grunt takes the footman three squares
           off or the knight at four. */
        for (type_index = 0; type_index < 112; type_index++) {
            trace_write("# bne-trace event=unit-type-priority type=%lu "
                    "priority=%lu",
                    (unsigned long) type_index,
                    (unsigned long) BNE_202_UNIT_TYPE_PRIORITY[type_index]);
        }
        /* The half-width of the box the reaction scan is allowed to see,
           which decides whether a second enemy is a candidate at all. */
        for (type_index = 0; type_index < 112; type_index++) {
            trace_write("# bne-trace event=unit-type-react type=%lu "
                    "computer=%lu person=%lu",
                    (unsigned long) type_index,
                    (unsigned long) BNE_202_REACT_RANGE_COMPUTER[type_index],
                    (unsigned long) BNE_202_REACT_RANGE_PERSON[type_index]);
        }
        /* How long a swing may not be shortened below. */
        for (type_index = 0; type_index < 112; type_index++) {
            trace_write("# bne-trace event=unit-type-anim-floor type=%lu "
                    "floor=%lu",
                    (unsigned long) type_index,
                    (unsigned long) BNE_202_ANIMATION_FLOOR[type_index]);
        }
    }
    if (cycle == 1 && trace_ai_build_state) {
        for (player = 0; player < 8; player++) {
            const BYTE *state = BNE_202_AI_PLAYER_STATE
                    + player * BNE_202_AI_PLAYER_STATE_BYTES;
            const BYTE *list = *(const BYTE * const *)
                    (state + BNE_202_AI_BUILD_LIST_OFFSET);
            const BYTE *availability = BNE_202_AI_AVAILABILITY
                    + player * BNE_202_AI_AVAILABILITY_BYTES;
            char entries[1024];
            size_t used = 0;
            int position;

            if (BNE_202_PLAYER_CONTROLLERS[player] != 1 || list == NULL) {
                continue;
            }
            entries[0] = '\0';
            for (position = 0; position < BNE_202_AI_AVAILABILITY_BYTES;
                    position++) {
                unsigned int code = (unsigned int) list[position];
                unsigned int even_count = 0;
                unsigned int odd_count = 0;
                int written;
                if ((code & ~1U) >= BNE_202_AI_BUILDING_TYPE_FIRST
                        && (code & ~1U) < BNE_202_AI_BUILDING_TYPE_FIRST
                            + BNE_202_AI_BUILDING_TYPE_COUNT) {
                    unsigned int base = (unsigned int) player
                            * BNE_202_AI_BUILDING_TYPE_COUNT
                            + (code & ~1U)
                            - BNE_202_AI_BUILDING_TYPE_FIRST;
                    even_count = (unsigned int)
                            BNE_202_AI_BUILDING_COUNTS[base];
                    odd_count = (unsigned int)
                            BNE_202_AI_BUILDING_COUNTS[base + 1];
                }
                written = snprintf(entries + used, sizeof(entries) - used,
                        "%s%02x:%u:%u/%u", position == 0 ? "" : ",",
                        code, (unsigned int) availability[position],
                        even_count, odd_count);
                if (written < 0 || (size_t) written >= sizeof(entries) - used) {
                    break;
                }
                used += (size_t) written;
                if (list[position] == 0xff) {
                    break;
                }
            }
            trace_write("# bne-trace event=ai-build-state player=%d "
                    "profile=%u length=%u entries=%s", player,
                    (unsigned int) BNE_202_AI_PROFILE_IDS[player],
                    (unsigned int) state[BNE_202_AI_BUILD_LIST_OFFSET - 1],
                    entries);
        }
    }
    for (player = 0; player < BNE_PLAYER_COUNT; player++) {
        if (BNE_202_PLAYER_CONTROLLERS[player] == BNE_CONTROLLER_NOBODY) {
            continue;
        }
        trace_write("p %d gold %lu wood %lu oil %lu", player,
                (unsigned long) BNE_202_PLAYER_GOLD[player],
                (unsigned long) BNE_202_PLAYER_LUMBER[player],
                (unsigned long) BNE_202_PLAYER_OIL[player]);
    }
    if (pool == NULL) {
        trace_write("# bne-trace event=invalid-unit-pool cycle=%ld", cycle);
        return;
    }
    if (pool_count > BNE_UNIT_LIMIT) {
        trace_write("# bne-trace event=clamped-unit-pool cycle=%ld count=%lu",
                cycle, (unsigned long) pool_count);
        pool_count = BNE_UNIT_LIMIT;
    }
    for (index = 0; index < pool_count; index++) {
        const BYTE *unit = pool + index * BNE_UNIT_BYTES;
        BYTE flags = unit[BNE_UNIT_FLAGS3];
        BYTE type = unit[BNE_UNIT_TYPE];
        if ((flags & (BNE_UNIT_FREE | BNE_UNIT_DEAD)) != 0) {
            continue;
        }
        trace_write("u %lu %s p%u %u %u hp %u o %s%s",
                (unsigned long) index, bne_unit_type_name(type),
                (unsigned int) unit[BNE_UNIT_OWNER],
                (unsigned int) read_word(unit, BNE_UNIT_X),
                (unsigned int) read_word(unit, BNE_UNIT_Y),
                (unsigned int) read_word(unit, BNE_UNIT_HP),
                bne_order_name(unit[BNE_UNIT_ORDER]),
                (flags & BNE_UNIT_HIDDEN) != 0 ? " removed" : "");
    }
    trace_selected_unit_components(cycle, pool, pool_count);
    if (state_stream_requested && !state_stream_failed
            && (!state_snapshot_cycle((DWORD) cycle, pool, pool_count)
                || !state_snapshot_aux((DWORD) cycle))) {
        trace_write("# bne-trace event=state-stream-write-failed cycle=%ld",
                cycle);
    }
    trace_command_unit_state(cycle, pool, pool_count);
    if (trace_cycle_limit > 0 && cycle >= trace_cycle_limit) {
        trace_write("# bne-trace event=cycle-limit cycle=%ld", cycle);
        /* ExitProcess runs DLL detach callbacks while this old game is still
         * inside its update call. Wine's macOS driver can deadlock there and
         * leave a frozen window behind. Finish our own trace first, then end
         * only this process without loader teardown. */
        trace_close();
        if (!TerminateProcess(GetCurrentProcess(), 0)) {
            ExitProcess(1);
        }
    }
}

/* Optional diagnostic handshake for Branch Witness.  The ordinary oracle never
 * sets these variables and pays only one integer comparison per tick.  A
 * diagnostic run pauses immediately before the requested native tick, giving
 * the networkless host time to attach GDB, arm BTS and a hardware watchpoint,
 * and create the resume marker.  A timeout prevents an abandoned capture from
 * leaving the oracle process hung forever. */
static void branch_pause_before_cycle(LONG cycle) {
    HANDLE ready;
    DWORD started;
    char message[192];
    DWORD written;

    if (branch_pause_cycle <= 0 || cycle != branch_pause_cycle
            || branch_ready_path[0] == '\0' || branch_resume_path[0] == '\0') {
        return;
    }
    DeleteFileA(branch_ready_path);
    DeleteFileA(branch_resume_path);
    ready = CreateFileA(branch_ready_path, GENERIC_WRITE, FILE_SHARE_READ,
            NULL, CREATE_ALWAYS, FILE_ATTRIBUTE_NORMAL, NULL);
    if (ready == INVALID_HANDLE_VALUE) {
        trace_write("# bne-trace event=branch-pause-failed cycle=%ld error=%lu",
                cycle, (unsigned long) GetLastError());
        return;
    }
    snprintf(message, sizeof(message),
            "pid=%lu cycle=%ld pool=%p\n",
            (unsigned long) GetCurrentProcessId(), cycle,
            (void *) *BNE_202_UNIT_POOL_POINTER);
    WriteFile(ready, message, (DWORD) strlen(message), &written, NULL);
    FlushFileBuffers(ready);
    CloseHandle(ready);
    trace_write("# bne-trace event=branch-pause-ready cycle=%ld pid=%lu",
            cycle, (unsigned long) GetCurrentProcessId());
    started = GetTickCount();
    while (GetFileAttributesA(branch_resume_path) == INVALID_FILE_ATTRIBUTES) {
        if (GetTickCount() - started >= 120000UL) {
            trace_write("# bne-trace event=branch-pause-timeout cycle=%ld",
                    cycle);
            return;
        }
        Sleep(10);
    }
    trace_write("# bne-trace event=branch-pause-resume cycle=%ld", cycle);
}

/* Preserve the pre-cycle scheduler state for the only retail unit whose idle
 * callback can change semantic state. BNE staggers action execution with the
 * animation timer, so the critter's random walk is not evaluated every tick. */
static void trace_critter_scheduler_state(const BYTE *pool, DWORD pool_count) {
    DWORD index;

    for (index = 0; index < pool_count; index++) {
        const BYTE *unit = pool + index * BNE_UNIT_BYTES;
        if ((unit[BNE_UNIT_FLAGS3] & (BNE_UNIT_FREE | BNE_UNIT_DEAD)) == 0
                && (trace_ai_build_state || unit[BNE_UNIT_TYPE] == 57
                    || unit[BNE_UNIT_ANIMATION_TIMER] == 1)) {
            trace_write("# bne-trace event=unit-scheduler unit=%lu type=%u "
                    "timer=%u sequence=%u sequence-flags=%u animation=%u "
                    "frame=%u face=%u "
                    "order=%u flags28=%u flags94=%u flags95=%u idle-timer=%u",
                    (unsigned long) index,
                    (unsigned int) unit[BNE_UNIT_TYPE],
                    (unsigned int) unit[BNE_UNIT_ANIMATION_TIMER],
                    (unsigned int) read_word(unit, BNE_UNIT_SEQUENCE),
                    (unsigned int) unit[BNE_UNIT_SEQUENCE_FLAGS],
                    (unsigned int) unit[BNE_UNIT_ANIMATION],
                    (unsigned int) unit[BNE_UNIT_FRAME],
                    (unsigned int) unit[BNE_UNIT_FACE],
                    (unsigned int) unit[BNE_UNIT_ORDER],
                    (unsigned int) unit[0x1c],
                    (unsigned int) unit[0x5e],
                    (unsigned int) unit[0x5f],
                    (unsigned int) unit[0x0d]);
        }
    }
}

static void trace_initialization_semantics(const char *phase, LONG phase_index,
        const BYTE *pool, DWORD pool_count) {
    DWORD index;
    int player;

    for (player = 0; player < BNE_PLAYER_COUNT; player++) {
        if (BNE_202_PLAYER_CONTROLLERS[player] != BNE_CONTROLLER_NOBODY) {
            trace_write("# bne-trace event=initial-bank phase=%s index=%ld "
                    "player=%d gold=%lu wood=%lu oil=%lu", phase,
                    phase_index, player,
                    (unsigned long) BNE_202_PLAYER_GOLD[player],
                    (unsigned long) BNE_202_PLAYER_LUMBER[player],
                    (unsigned long) BNE_202_PLAYER_OIL[player]);
        }
    }
    if (pool == NULL) {
        return;
    }
    for (index = 0; index < pool_count; index++) {
        const BYTE *unit = pool + index * BNE_UNIT_BYTES;
        if ((unit[BNE_UNIT_FLAGS3] & (BNE_UNIT_FREE | BNE_UNIT_DEAD)) == 0
                && unit[BNE_UNIT_ORDER] != 2) {
            trace_write("# bne-trace event=initial-order phase=%s index=%ld "
                    "unit=%lu type=%u owner=%u order=%u next-order=%u "
                    "x=%u y=%u order-x=%u order-y=%u", phase, phase_index,
                    (unsigned long) index, (unsigned int) unit[BNE_UNIT_TYPE],
                    (unsigned int) unit[BNE_UNIT_OWNER],
                    (unsigned int) unit[BNE_UNIT_ORDER],
                    (unsigned int) unit[BNE_UNIT_NEXT_ORDER],
                    (unsigned int) read_word(unit, BNE_UNIT_X),
                    (unsigned int) read_word(unit, BNE_UNIT_Y),
                    (unsigned int) read_word(unit, BNE_UNIT_ORDER_X),
                    (unsigned int) read_word(unit, BNE_UNIT_ORDER_Y));
        }
    }
}

static void trace_ai_build_boundaries(const char *phase, LONG phase_index) {
    int player;

    if (!trace_ai_build_state) {
        return;
    }
    for (player = 0; player < 8; player++) {
        const BYTE *state = BNE_202_AI_PLAYER_STATE
                + player * BNE_202_AI_PLAYER_STATE_BYTES;
        if (BNE_202_PLAYER_CONTROLLERS[player] == 1) {
            char raw[3 * BNE_202_AI_PLAYER_STATE_BYTES + 1];
            size_t used = 0;
            int offset;

            raw[0] = '\0';
            for (offset = 0; offset < BNE_202_AI_PLAYER_STATE_BYTES;
                    offset++) {
                int written = snprintf(raw + used, sizeof(raw) - used,
                        "%s%02x", offset == 0 ? "" : ",",
                        (unsigned int) state[offset]);
                if (written < 0 || (size_t) written >= sizeof(raw) - used) {
                    break;
                }
                used += (size_t) written;
            }
            trace_write("# bne-trace event=ai-build-boundary phase=%s "
                    "index=%ld player=%d profile=%u length=%u state=%s", phase,
                    phase_index, player,
                    (unsigned int) BNE_202_AI_PROFILE_IDS[player],
                    (unsigned int) state[BNE_202_AI_BUILD_LIST_OFFSET - 1], raw);
        }
    }
}

static void __cdecl traced_game_tick(void) {
    BYTE *pool;
    DWORD pool_count;

    disable_tips_if_requested();
    pool = *BNE_202_UNIT_POOL_POINTER;
    pool_count = *BNE_202_UNIT_POOL_COUNT;
    if (pool == NULL || pool_count == 0) {
        /* This call site is reachable only from BNE's active timed game loop.
         * Refuse to invent a cycle number if its scenario state is absent. */
        trace_write("# bne-trace event=simulation-boundary-not-ready");
        original_game_tick();
        return;
    }
    if (!match_ready) {
        match_ready = TRUE;
        trace_write("# bne-trace event=match-ready slots=%lu async-seed=%lu",
                (unsigned long) pool_count,
                (unsigned long) *BNE_202_ASYNC_RANDOM_SEED);
        trace_critter_scheduler_state(pool, pool_count);
    }
    /* Pair every committed after-state with its own pre-tick state.  Emitting
       this only for cycle one made later write ledgers infer from the previous
       after-state and hid transient writes that returned a byte to itself. */
    trace_ai_build_boundaries("game-before", traced_cycles + 1);
    apply_commands(traced_cycles + 1);
    branch_pause_before_cycle(traced_cycles + 1);
    original_game_tick();
    snapshot_cycle();
}

static void __cdecl traced_warmup_tick(void) {
    static LONG warmup_index = 0;
    BYTE *pool = *BNE_202_UNIT_POOL_POINTER;
    DWORD pool_count = *BNE_202_UNIT_POOL_COUNT;
    LONG index = InterlockedIncrement(&warmup_index);

    trace_write("# bne-trace event=warmup-boundary index=%ld phase=before "
            "async-seed=%lu", index,
            (unsigned long) *BNE_202_ASYNC_RANDOM_SEED);
    trace_critter_scheduler_state(pool, pool_count);
    trace_initialization_semantics("warmup-before", index, pool, pool_count);
    trace_ai_build_boundaries("warmup-before", index);
    original_game_tick();
    trace_write("# bne-trace event=warmup-boundary index=%ld phase=after "
            "async-seed=%lu", index,
            (unsigned long) *BNE_202_ASYNC_RANDOM_SEED);
    trace_critter_scheduler_state(pool, pool_count);
    trace_initialization_semantics("warmup-after", index, pool, pool_count);
    trace_ai_build_boundaries("warmup-after", index);
}

static BOOL parse_campaign_scenario(const char *source) {
    char normalized[MAX_PATH];
    char canonical[MAX_PATH];
    const char *number_start = NULL;
    char *number_end = NULL;
    unsigned long mission;
    size_t index;
    int campaign_base = 0;
    int mission_count = 0;
    int race_offset = 0;
    const char *campaign_name = NULL;
    const char *file_name = NULL;

    if (source == NULL || source[0] == '\0' || strlen(source) >= MAX_PATH) {
        return FALSE;
    }
    for (index = 0; source[index] != '\0'; index++) {
        unsigned char ch = (unsigned char) source[index];
        normalized[index] = (char) tolower(ch == '/' ? '\\' : ch);
    }
    normalized[index] = '\0';

    if (strncmp(normalized, "campaign\\human\\human", 20) == 0) {
        number_start = normalized + 20;
        mission_count = 14;
        campaign_name = "Human";
        file_name = "Human";
    } else if (strncmp(normalized, "campaign\\orc\\orc", 16) == 0) {
        number_start = normalized + 16;
        mission_count = 14;
        race_offset = 1;
        campaign_name = "Orc";
        file_name = "Orc";
    } else if (strncmp(normalized, "campaign\\xhuman\\2xhum", 21) == 0) {
        number_start = normalized + 21;
        campaign_base = 28;
        mission_count = 12;
        campaign_name = "XHuman";
        file_name = "2XHum";
    } else if (strncmp(normalized, "campaign\\xorc\\2xorc", 19) == 0) {
        number_start = normalized + 19;
        campaign_base = 28;
        mission_count = 12;
        race_offset = 1;
        campaign_name = "XOrc";
        file_name = "2XOrc";
    } else {
        return FALSE;
    }

    mission = strtoul(number_start, &number_end, 10);
    if (number_end == number_start || strcmp(number_end, ".pud") != 0
            || mission < 1 || mission > (unsigned long) mission_count) {
        return FALSE;
    }
    bootstrap_selector = (WORD) (campaign_base
            + ((int) mission - 1) * 2 + race_offset);
    if (bootstrap_selector < 28) {
        bootstrap_resource = (WORD) (0x52c8 + bootstrap_selector);
    } else {
        bootstrap_resource = (WORD) (0x53aa + bootstrap_selector);
    }
    bootstrap_orc = (BYTE) race_offset;
    snprintf(canonical, sizeof(canonical), "Campaign\\%s\\%s%02lu.pud",
            campaign_name, file_name, mission);
    lstrcpynA(bootstrap_scenario, canonical, MAX_PATH);
    return TRUE;
}

static void read_bootstrap_environment(void) {
    char scenario[MAX_PATH];
    DWORD length = GetEnvironmentVariableA(
            "CHONK_BNE_SCENARIO", scenario, sizeof(scenario));

    if (length == 0) {
        return;
    }
    if (length >= sizeof(scenario) || !parse_campaign_scenario(scenario)) {
        trace_write("# bne-trace event=scenario-bootstrap-rejected "
                "reason=unsupported-scenario");
        return;
    }
    InterlockedExchange(&bootstrap_pending, 1);
    trace_write("# bne-trace event=scenario-bootstrap-armed "
            "scenario=\"%s\" selector=%u resource=0x%04x race=%s",
            bootstrap_scenario, (unsigned int) bootstrap_selector,
            (unsigned int) bootstrap_resource,
            bootstrap_orc != 0 ? "orc" : "human");
}

static WORD __cdecl traced_main_state(void) {
    WORD observed;

    disable_tips_if_requested();
    observed = original_main_state();
    disable_tips_if_requested();

    if (InterlockedCompareExchange(&bootstrap_pending, 0, 1) != 1) {
        return observed;
    }
    *BNE_202_CAMPAIGN_SELECTOR = bootstrap_selector;
    *BNE_202_CAMPAIGN_RESOURCE = bootstrap_resource;
    *BNE_202_CAMPAIGN_ORC = bootstrap_orc;
    /* This flag bypasses the campaign's blocking interlude/objectives UI.
     * The new-game call hook clears it before any scenario state is built. */
    *BNE_202_CUSTOM_GAME_FLAG = 1;
    *BNE_202_MAIN_STATE = 3;
    InterlockedExchange(&bootstrap_dispatching, 1);
    trace_write("# bne-trace event=scenario-bootstrap-dispatched "
            "scenario=\"%s\" observed-state=%u selector=%u resource=0x%04x",
            bootstrap_scenario, (unsigned int) observed,
            (unsigned int) bootstrap_selector,
            (unsigned int) bootstrap_resource);
    return 3;
}

static int __cdecl traced_new_game(int saved_game, int scenario_file,
        int scenario_size) {
    int result;

    disable_tips_if_requested();
    /* The normal installed-game path can arrive here with BNE's master RNG
     * already initialized, which skips its conditional time(NULL) seed call.
     * Apply the oracle seed at this unconditional boundary. BNE immediately
     * copies the master value into the unit-construction RNG before loading
     * the PUD, so the constructor's facing and animation delay are repeatable. */
    if (deterministic_seed_enabled && original_master_seed != NULL) {
        original_master_seed(deterministic_seed);
        trace_write("# bne-trace event=master-seed-forced seed=%lu",
                (unsigned long) deterministic_seed);
    }
    if (InterlockedExchange(&bootstrap_dispatching, 0) == 1) {
        *BNE_202_CUSTOM_GAME_FLAG = 0;
        trace_write("# bne-trace event=scenario-bootstrap-ui-bypassed "
                "scenario=\"%s\"", bootstrap_scenario);
    }
    result = original_new_game(saved_game, scenario_file, scenario_size);
    disable_tips_if_requested();
    return result;
}

static BOOL WINAPI traced_storm_open_archive(const char *path, DWORD priority,
        DWORD flags, HANDLE *archive) {
    BOOL result = original_storm_open_archive(path, priority, flags, archive);
    DWORD error = GetLastError();
    char escaped[MAX_PATH * 2];

    json_escape(path == NULL ? "" : path, escaped, sizeof(escaped));
    if (!result && wine_cd_fallback && flags == 1
            && error == ERROR_INVALID_DRIVE) {
        SetLastError(ERROR_SUCCESS);
        result = original_storm_open_archive(path, priority, 0, archive);
        error = GetLastError();
        trace_write("# bne-trace event=wine-cd-fallback path=\"%s\" "
                "result=%d handle=%p error=%lu",
                escaped, result,
                archive == NULL ? NULL : (void *) *archive,
                (unsigned long) error);
    }
    trace_write("# bne-trace event=storm-open-archive path=\"%s\" "
            "priority=%lu flags=%lu result=%d handle=%p error=%lu",
            escaped, (unsigned long) priority, (unsigned long) flags, result,
            archive == NULL ? NULL : (void *) *archive, (unsigned long) error);
    SetLastError(error);
    return result;
}

static BOOL WINAPI traced_storm_open_file(HANDLE archive, const char *path,
        DWORD scope, HANDLE *file) {
    BOOL result = original_storm_open_file(archive, path, scope, file);
    DWORD error = GetLastError();
    char escaped[MAX_PATH * 2];
    json_escape(path == NULL ? "" : path, escaped, sizeof(escaped));
    trace_write("# bne-trace event=storm-open-file archive=%p path=\"%s\" "
            "scope=%lu result=%d handle=%p error=%lu",
            (void *) archive, escaped, (unsigned long) scope, result,
            file == NULL ? NULL : (void *) *file, (unsigned long) error);
    SetLastError(error);
    return result;
}

static BOOL executable_page_contains(const void *address) {
    MEMORY_BASIC_INFORMATION info;
    HMODULE target = GetModuleHandleA(NULL);
    if (VirtualQuery(address, &info, sizeof(info)) != sizeof(info)) {
        return FALSE;
    }
    return info.State == MEM_COMMIT && info.AllocationBase == target;
}

static int __cdecl traced_sync_dispatch(BYTE *packet, DWORD packet_bytes) {
    replay_schedule_record *record = NULL;
    BYTE *expected_packet = NULL;
    DWORD live_player = *BNE_202_NETWORK_PLAYER_INDEX;
    BOOL status_matches = FALSE;
    BOOL identity_matches = FALSE;

    /* Multiplayer setup uses this same dispatcher before the timed game loop
     * exists.  InSight starts replay delivery only after the map and lobby
     * have entered the game.  Preserve those setup packets verbatim; consuming
     * record zero here makes an otherwise exact replay fail on lobby traffic. */
    if (!replay_game_ready) {
        return original_sync_dispatch(packet, packet_bytes);
    }

    if (replay_schedule_valid
            && replay_schedule_consumed < replay_schedule_records
            && replay_schedule_cursor != NULL
            && (size_t) (replay_schedule_end - replay_schedule_cursor)
                    >= sizeof(*record)) {
        record = (replay_schedule_record *) (void *) replay_schedule_cursor;
        expected_packet = replay_schedule_cursor + sizeof(*record);
        status_matches = memcmp(record->slot_status,
                BNE_202_PLAYER_CONTROLLERS, sizeof(record->slot_status)) == 0;
        identity_matches = record->index == replay_schedule_consumed
                && record->network_player == live_player
                && status_matches;
        if (identity_matches) {
            /* The schedule is the input, not an observation.  Feed the exact
             * recorded bytes through retail's unchanged dispatcher.  This is
             * the headless equivalent of InSight playback and deliberately
             * ignores packets generated by the local UI. */
            trace_write("# bne-trace event=replay-dispatch-injected "
                    "record=%lu player=%lu bytes=%lu live-bytes=%lu "
                    "trace-cycle=%ld",
                    (unsigned long) record->index,
                    (unsigned long) live_player,
                    (unsigned long) record->packet_bytes,
                    (unsigned long) packet_bytes, traced_cycles);
        } else {
            replay_schedule_valid = FALSE;
            trace_write("# bne-trace event=replay-dispatch-mismatch "
                    "record=%lu expected-player=%lu live-player=%lu "
                    "expected-bytes=%lu live-bytes=%lu "
                    "slot-status=%s",
                    (unsigned long) replay_schedule_consumed,
                    (unsigned long) record->network_player,
                    (unsigned long) live_player,
                    (unsigned long) record->packet_bytes,
                    (unsigned long) packet_bytes,
                    status_matches ? "match" : "mismatch");
        }
        replay_schedule_cursor = expected_packet + record->packet_bytes;
        replay_schedule_consumed++;
        if (replay_schedule_valid
                && replay_schedule_consumed == replay_schedule_records) {
            trace_write("# bne-trace event=replay-schedule-complete "
                    "records=%lu",
                    (unsigned long) replay_schedule_records);
        }
    } else {
        replay_schedule_valid = FALSE;
        trace_write("# bne-trace event=replay-dispatch-mismatch "
                "record=%lu reason=unexpected-dispatch player=%lu bytes=%lu",
                (unsigned long) replay_schedule_consumed,
                (unsigned long) live_player,
                (unsigned long) packet_bytes);
    }
    if (identity_matches) {
        return original_sync_dispatch(expected_packet, record->packet_bytes);
    }
    return original_sync_dispatch(packet, packet_bytes);
}

static BOOL install_sync_dispatch_hook(void) {
    static const BYTE expected[] = {0xe8, 0x90, 0x02, 0x00, 0x00};
    BYTE replacement[sizeof(expected)];
    int32_t old_relative;
    int32_t new_relative;
    DWORD old_protection;

    if (!replay_schedule_requested) {
        return TRUE;
    }
    if (!executable_page_contains(BNE_202_SYNC_DISPATCH_CALL)
            || memcmp(BNE_202_SYNC_DISPATCH_CALL, expected,
                    sizeof(expected)) != 0) {
        trace_write("# bne-trace event=replay-dispatch-hook-rejected "
                "reason=signature");
        return FALSE;
    }
    memcpy(&old_relative, BNE_202_SYNC_DISPATCH_CALL + 1,
            sizeof(old_relative));
    original_sync_dispatch = (sync_dispatch_function) (void *)
            (BNE_202_SYNC_DISPATCH_CALL + sizeof(expected) + old_relative);
    if ((BYTE *) (void *) original_sync_dispatch
            != BNE_202_SYNC_DISPATCH_TARGET) {
        original_sync_dispatch = NULL;
        trace_write("# bne-trace event=replay-dispatch-hook-rejected "
                "reason=target");
        return FALSE;
    }
    replacement[0] = 0xe8;
    new_relative = (int32_t) ((BYTE *) (void *) traced_sync_dispatch
            - (BNE_202_SYNC_DISPATCH_CALL + sizeof(replacement)));
    memcpy(replacement + 1, &new_relative, sizeof(new_relative));
    if (!VirtualProtect(BNE_202_SYNC_DISPATCH_CALL, sizeof(replacement),
            PAGE_EXECUTE_READWRITE, &old_protection)) {
        trace_write("# bne-trace event=replay-dispatch-hook-rejected "
                "reason=virtual-protect error=%lu",
                (unsigned long) GetLastError());
        original_sync_dispatch = NULL;
        return FALSE;
    }
    memcpy(BNE_202_SYNC_DISPATCH_CALL, replacement, sizeof(replacement));
    FlushInstructionCache(GetCurrentProcess(), BNE_202_SYNC_DISPATCH_CALL,
            sizeof(replacement));
    VirtualProtect(BNE_202_SYNC_DISPATCH_CALL, sizeof(replacement),
            old_protection, &old_protection);
    trace_write("# bne-trace event=replay-dispatch-hook-installed "
            "site=0x0047800b target=0x004782a0");
    return TRUE;
}

static BOOL install_tick_hook(void) {
    static const BYTE expected[] = {0xe8, 0xd3, 0x0e, 0x03, 0x00};
    BYTE replacement[sizeof(expected)];
    int32_t old_relative;
    int32_t new_relative;
    DWORD old_protection;

    if (!executable_page_contains(BNE_202_TICK_CALL)) {
        trace_write("# bne-trace event=hook-rejected reason=address-not-in-target");
        return FALSE;
    }
    if (memcmp(BNE_202_TICK_CALL, expected, sizeof(expected)) != 0) {
        trace_write("# bne-trace event=hook-rejected reason=signature-mismatch");
        return FALSE;
    }
    memcpy(&old_relative, BNE_202_TICK_CALL + 1, sizeof(old_relative));
    original_game_tick = (game_tick_function) (void *)
            (BNE_202_TICK_CALL + sizeof(expected) + old_relative);
    if ((BYTE *) (void *) original_game_tick != BNE_202_TICK_TARGET) {
        original_game_tick = NULL;
        trace_write("# bne-trace event=hook-rejected reason=target-mismatch");
        return FALSE;
    }

    replacement[0] = 0xe8;
    new_relative = (int32_t) ((BYTE *) (void *) traced_game_tick
            - (BNE_202_TICK_CALL + sizeof(replacement)));
    memcpy(replacement + 1, &new_relative, sizeof(new_relative));
    if (!VirtualProtect(BNE_202_TICK_CALL, sizeof(replacement),
            PAGE_EXECUTE_READWRITE, &old_protection)) {
        trace_write("# bne-trace event=hook-rejected reason=virtual-protect error=%lu",
                (unsigned long) GetLastError());
        original_game_tick = NULL;
        return FALSE;
    }
    memcpy(BNE_202_TICK_CALL, replacement, sizeof(replacement));
    FlushInstructionCache(GetCurrentProcess(), BNE_202_TICK_CALL,
            sizeof(replacement));
    VirtualProtect(BNE_202_TICK_CALL, sizeof(replacement), old_protection,
            &old_protection);
    trace_write("# bne-trace event=hook-installed site=0x00421238 target=0x00452110");
    return TRUE;
}

static BOOL install_warmup_tick_hook(BYTE *site, const BYTE expected[5],
        const char *name) {
    BYTE replacement[5];
    int32_t old_relative;
    int32_t new_relative;
    DWORD old_protection;

    if (!executable_page_contains(site)
            || memcmp(site, expected, sizeof(replacement)) != 0) {
        trace_write("# bne-trace event=warmup-hook-rejected name=%s", name);
        return FALSE;
    }
    memcpy(&old_relative, site + 1, sizeof(old_relative));
    if (site + sizeof(replacement) + old_relative != BNE_202_TICK_TARGET) {
        trace_write("# bne-trace event=warmup-hook-rejected name=%s "
                "reason=target", name);
        return FALSE;
    }
    replacement[0] = 0xe8;
    new_relative = (int32_t) ((BYTE *) (void *) traced_warmup_tick
            - (site + sizeof(replacement)));
    memcpy(replacement + 1, &new_relative, sizeof(new_relative));
    if (!VirtualProtect(site, sizeof(replacement), PAGE_EXECUTE_READWRITE,
            &old_protection)) {
        trace_write("# bne-trace event=warmup-hook-rejected name=%s "
                "reason=virtual-protect", name);
        return FALSE;
    }
    memcpy(site, replacement, sizeof(replacement));
    FlushInstructionCache(GetCurrentProcess(), site, sizeof(replacement));
    VirtualProtect(site, sizeof(replacement), old_protection, &old_protection);
    trace_write("# bne-trace event=warmup-hook-installed name=%s", name);
    return TRUE;
}

static BOOL install_warmup_tick_hooks(void) {
    static const BYTE first[] = {0xe8, 0x44, 0x15, 0x03, 0x00};
    static const BYTE second[] = {0xe8, 0x65, 0x14, 0x03, 0x00};
    BOOL first_ok = install_warmup_tick_hook(BNE_202_WARMUP_TICK_1_CALL,
            first, "first");
    BOOL second_ok = install_warmup_tick_hook(BNE_202_WARMUP_TICK_2_CALL,
            second, "second");
    return first_ok && second_ok;
}

static BOOL install_main_state_hook(void) {
    static const BYTE expected[] = {0xe8, 0x33, 0x61, 0xff, 0xff};
    BYTE replacement[sizeof(expected)];
    int32_t old_relative;
    int32_t new_relative;
    DWORD old_protection;

    if (!executable_page_contains(BNE_202_MAIN_STATE_CALL)) {
        trace_write("# bne-trace event=bootstrap-hook-rejected "
                "reason=address-not-in-target");
        return FALSE;
    }
    if (memcmp(BNE_202_MAIN_STATE_CALL, expected, sizeof(expected)) != 0) {
        trace_write("# bne-trace event=bootstrap-hook-rejected "
                "reason=signature-mismatch");
        return FALSE;
    }
    memcpy(&old_relative, BNE_202_MAIN_STATE_CALL + 1, sizeof(old_relative));
    original_main_state = (main_state_function) (void *)
            (BNE_202_MAIN_STATE_CALL + sizeof(expected) + old_relative);
    if ((BYTE *) (void *) original_main_state != BNE_202_MAIN_STATE_TARGET) {
        original_main_state = NULL;
        trace_write("# bne-trace event=bootstrap-hook-rejected "
                "reason=target-mismatch");
        return FALSE;
    }

    replacement[0] = 0xe8;
    new_relative = (int32_t) ((BYTE *) (void *) traced_main_state
            - (BNE_202_MAIN_STATE_CALL + sizeof(replacement)));
    memcpy(replacement + 1, &new_relative, sizeof(new_relative));
    if (!VirtualProtect(BNE_202_MAIN_STATE_CALL, sizeof(replacement),
            PAGE_EXECUTE_READWRITE, &old_protection)) {
        trace_write("# bne-trace event=bootstrap-hook-rejected "
                "reason=virtual-protect error=%lu",
                (unsigned long) GetLastError());
        original_main_state = NULL;
        return FALSE;
    }
    memcpy(BNE_202_MAIN_STATE_CALL, replacement, sizeof(replacement));
    FlushInstructionCache(GetCurrentProcess(), BNE_202_MAIN_STATE_CALL,
            sizeof(replacement));
    VirtualProtect(BNE_202_MAIN_STATE_CALL, sizeof(replacement),
            old_protection, &old_protection);
    trace_write("# bne-trace event=bootstrap-hook-installed "
            "site=0x0042a348 target=0x00420480");
    return TRUE;
}

static BOOL install_new_game_hook(void) {
    static const BYTE expected[] = {0xe8, 0x3a, 0x52, 0xff, 0xff};
    BYTE replacement[sizeof(expected)];
    int32_t old_relative;
    int32_t new_relative;
    DWORD old_protection;

    if (!executable_page_contains(BNE_202_NEW_GAME_CALL)) {
        trace_write("# bne-trace event=new-game-hook-rejected "
                "reason=address-not-in-target");
        return FALSE;
    }
    if (memcmp(BNE_202_NEW_GAME_CALL, expected, sizeof(expected)) != 0) {
        trace_write("# bne-trace event=new-game-hook-rejected "
                "reason=signature-mismatch");
        return FALSE;
    }
    memcpy(&old_relative, BNE_202_NEW_GAME_CALL + 1, sizeof(old_relative));
    original_new_game = (new_game_function) (void *)
            (BNE_202_NEW_GAME_CALL + sizeof(expected) + old_relative);
    if ((BYTE *) (void *) original_new_game != BNE_202_NEW_GAME_TARGET) {
        original_new_game = NULL;
        trace_write("# bne-trace event=new-game-hook-rejected "
                "reason=target-mismatch");
        return FALSE;
    }

    replacement[0] = 0xe8;
    new_relative = (int32_t) ((BYTE *) (void *) traced_new_game
            - (BNE_202_NEW_GAME_CALL + sizeof(replacement)));
    memcpy(replacement + 1, &new_relative, sizeof(new_relative));
    if (!VirtualProtect(BNE_202_NEW_GAME_CALL, sizeof(replacement),
            PAGE_EXECUTE_READWRITE, &old_protection)) {
        trace_write("# bne-trace event=new-game-hook-rejected "
                "reason=virtual-protect error=%lu",
                (unsigned long) GetLastError());
        original_new_game = NULL;
        return FALSE;
    }
    memcpy(BNE_202_NEW_GAME_CALL, replacement, sizeof(replacement));
    FlushInstructionCache(GetCurrentProcess(), BNE_202_NEW_GAME_CALL,
            sizeof(replacement));
    VirtualProtect(BNE_202_NEW_GAME_CALL, sizeof(replacement),
            old_protection, &old_protection);
    trace_write("# bne-trace event=new-game-hook-installed "
            "site=0x0042a4a1 target=0x0041f6e0");
    return TRUE;
}

static BOOL install_load_scenario_hook(void) {
    static const BYTE expected[] = {0xe8, 0x1d, 0xcc, 0x00, 0x00};
    BYTE replacement[sizeof(expected)];
    int32_t old_relative;
    int32_t new_relative;
    DWORD old_protection;

    if (!executable_page_contains(BNE_202_LOAD_SCENARIO_CALL)) {
        trace_write("# bne-trace event=scenario-load-hook-rejected "
                "reason=address-not-in-target");
        return FALSE;
    }
    if (memcmp(BNE_202_LOAD_SCENARIO_CALL, expected, sizeof(expected)) != 0) {
        trace_write("# bne-trace event=scenario-load-hook-rejected "
                "reason=signature-mismatch");
        return FALSE;
    }
    memcpy(&old_relative, BNE_202_LOAD_SCENARIO_CALL + 1,
            sizeof(old_relative));
    original_load_scenario = (load_scenario_function) (void *)
            (BNE_202_LOAD_SCENARIO_CALL + sizeof(expected) + old_relative);
    if ((BYTE *) (void *) original_load_scenario
            != BNE_202_LOAD_SCENARIO_TARGET) {
        original_load_scenario = NULL;
        trace_write("# bne-trace event=scenario-load-hook-rejected "
                "reason=target-mismatch");
        return FALSE;
    }

    replacement[0] = 0xe8;
    new_relative = (int32_t) ((BYTE *) (void *) traced_load_scenario
            - (BNE_202_LOAD_SCENARIO_CALL + sizeof(replacement)));
    memcpy(replacement + 1, &new_relative, sizeof(new_relative));
    if (!VirtualProtect(BNE_202_LOAD_SCENARIO_CALL, sizeof(replacement),
            PAGE_EXECUTE_READWRITE, &old_protection)) {
        trace_write("# bne-trace event=scenario-load-hook-rejected "
                "reason=virtual-protect error=%lu",
                (unsigned long) GetLastError());
        original_load_scenario = NULL;
        return FALSE;
    }
    memcpy(BNE_202_LOAD_SCENARIO_CALL, replacement, sizeof(replacement));
    FlushInstructionCache(GetCurrentProcess(), BNE_202_LOAD_SCENARIO_CALL,
            sizeof(replacement));
    VirtualProtect(BNE_202_LOAD_SCENARIO_CALL, sizeof(replacement),
            old_protection, &old_protection);
    trace_write("# bne-trace event=scenario-load-hook-installed "
            "site=0x0041face target=0x0042c6f0");
    return TRUE;
}

static BOOL install_master_seed_hook(void) {
    static const BYTE expected[] = {0xe8, 0x1a, 0xa1, 0x05, 0x00};
    BYTE replacement[sizeof(expected)];
    int32_t old_relative;
    int32_t new_relative;
    DWORD old_protection;

    if (!executable_page_contains(BNE_202_MASTER_SEED_CALL)) {
        trace_write("# bne-trace event=seed-hook-rejected "
                "reason=address-not-in-target");
        return FALSE;
    }
    if (memcmp(BNE_202_MASTER_SEED_CALL, expected, sizeof(expected)) != 0) {
        trace_write("# bne-trace event=seed-hook-rejected "
                "reason=signature-mismatch");
        return FALSE;
    }
    memcpy(&old_relative, BNE_202_MASTER_SEED_CALL + 1,
            sizeof(old_relative));
    original_master_seed = (master_seed_function) (void *)
            (BNE_202_MASTER_SEED_CALL + sizeof(expected) + old_relative);
    if ((BYTE *) (void *) original_master_seed
            != BNE_202_MASTER_SEED_TARGET) {
        original_master_seed = NULL;
        trace_write("# bne-trace event=seed-hook-rejected "
                "reason=target-mismatch");
        return FALSE;
    }

    replacement[0] = 0xe8;
    new_relative = (int32_t) ((BYTE *) (void *) traced_master_seed
            - (BNE_202_MASTER_SEED_CALL + sizeof(replacement)));
    memcpy(replacement + 1, &new_relative, sizeof(new_relative));
    if (!VirtualProtect(BNE_202_MASTER_SEED_CALL, sizeof(replacement),
            PAGE_EXECUTE_READWRITE, &old_protection)) {
        trace_write("# bne-trace event=seed-hook-rejected "
                "reason=virtual-protect error=%lu",
                (unsigned long) GetLastError());
        original_master_seed = NULL;
        return FALSE;
    }
    memcpy(BNE_202_MASTER_SEED_CALL, replacement, sizeof(replacement));
    FlushInstructionCache(GetCurrentProcess(), BNE_202_MASTER_SEED_CALL,
            sizeof(replacement));
    VirtualProtect(BNE_202_MASTER_SEED_CALL, sizeof(replacement),
            old_protection, &old_protection);
    trace_write("# bne-trace event=seed-hook-installed "
            "site=0x0041f751 target=0x00479870 seed=%lu",
            (unsigned long) deterministic_seed);
    return TRUE;
}

static BOOL install_async_random_hook(void) {
    static const BYTE expected[] = {0xa1, 0xec, 0x40, 0x4d, 0x00};
    BYTE replacement[sizeof(expected)];
    int32_t new_relative;
    DWORD old_protection;

    if (!executable_page_contains(BNE_202_ASYNC_RANDOM_TARGET)) {
        trace_write("# bne-trace event=async-hook-rejected reason=address");
        return FALSE;
    }
    if (memcmp(BNE_202_ASYNC_RANDOM_TARGET, expected,
            sizeof(expected)) != 0) {
        trace_write("# bne-trace event=async-hook-rejected reason=signature");
        return FALSE;
    }
    replacement[0] = 0xe9;
    new_relative = (int32_t) ((BYTE *) (void *) traced_async_random
            - (BNE_202_ASYNC_RANDOM_TARGET + sizeof(replacement)));
    memcpy(replacement + 1, &new_relative, sizeof(new_relative));
    if (!VirtualProtect(BNE_202_ASYNC_RANDOM_TARGET, sizeof(replacement),
            PAGE_EXECUTE_READWRITE, &old_protection)) {
        trace_write("# bne-trace event=async-hook-rejected "
                "reason=virtual-protect error=%lu",
                (unsigned long) GetLastError());
        return FALSE;
    }
    memcpy(BNE_202_ASYNC_RANDOM_TARGET, replacement, sizeof(replacement));
    FlushInstructionCache(GetCurrentProcess(), BNE_202_ASYNC_RANDOM_TARGET,
            sizeof(replacement));
    VirtualProtect(BNE_202_ASYNC_RANDOM_TARGET, sizeof(replacement),
            old_protection, &old_protection);
    trace_write("# bne-trace event=async-hook-installed target=0x00479820");
    return TRUE;
}

static BOOL install_sync_random_hook(void) {
    static const BYTE expected[] = {0xa1, 0xdc, 0x48, 0x4a, 0x00};
    BYTE replacement[sizeof(expected)];
    int32_t new_relative;
    DWORD old_protection;

    if (!executable_page_contains(BNE_202_SYNC_RANDOM_TARGET)) {
        trace_write("# bne-trace event=sync-rng-hook-rejected reason=address");
        return FALSE;
    }
    if (memcmp(BNE_202_SYNC_RANDOM_TARGET, expected,
            sizeof(expected)) != 0) {
        trace_write("# bne-trace event=sync-rng-hook-rejected reason=signature");
        return FALSE;
    }
    replacement[0] = 0xe9;
    new_relative = (int32_t) ((BYTE *) (void *) traced_sync_random
            - (BNE_202_SYNC_RANDOM_TARGET + sizeof(replacement)));
    memcpy(replacement + 1, &new_relative, sizeof(new_relative));
    if (!VirtualProtect(BNE_202_SYNC_RANDOM_TARGET, sizeof(replacement),
            PAGE_EXECUTE_READWRITE, &old_protection)) {
        trace_write("# bne-trace event=sync-rng-hook-rejected "
                "reason=virtual-protect error=%lu",
                (unsigned long) GetLastError());
        return FALSE;
    }
    memcpy(BNE_202_SYNC_RANDOM_TARGET, replacement, sizeof(replacement));
    FlushInstructionCache(GetCurrentProcess(), BNE_202_SYNC_RANDOM_TARGET,
            sizeof(replacement));
    VirtualProtect(BNE_202_SYNC_RANDOM_TARGET, sizeof(replacement),
            old_protection, &old_protection);
    trace_write("# bne-trace event=sync-rng-hook-installed "
            "target=0x004534c0");
    return TRUE;
}

static BOOL install_idle_hook(void) {
    static const BYTE expected[] = {0xe8, 0xd3, 0xfc, 0xff, 0xff};
    BYTE replacement[sizeof(expected)];
    int32_t old_relative;
    int32_t new_relative;
    DWORD old_protection;

    if (!executable_page_contains(BNE_202_IDLE_CALL)
            || memcmp(BNE_202_IDLE_CALL, expected, sizeof(expected)) != 0) {
        trace_write("# bne-trace event=idle-hook-rejected reason=signature");
        return FALSE;
    }
    memcpy(&old_relative, BNE_202_IDLE_CALL + 1, sizeof(old_relative));
    original_idle = (idle_function) (void *)
            (BNE_202_IDLE_CALL + sizeof(expected) + old_relative);
    if ((BYTE *) (void *) original_idle != BNE_202_IDLE_TARGET) {
        original_idle = NULL;
        trace_write("# bne-trace event=idle-hook-rejected reason=target");
        return FALSE;
    }
    replacement[0] = 0xe8;
    new_relative = (int32_t) ((BYTE *) (void *) traced_idle
            - (BNE_202_IDLE_CALL + sizeof(replacement)));
    memcpy(replacement + 1, &new_relative, sizeof(new_relative));
    if (!VirtualProtect(BNE_202_IDLE_CALL, sizeof(replacement),
            PAGE_EXECUTE_READWRITE, &old_protection)) {
        trace_write("# bne-trace event=idle-hook-rejected "
                "reason=virtual-protect");
        original_idle = NULL;
        return FALSE;
    }
    memcpy(BNE_202_IDLE_CALL, replacement, sizeof(replacement));
    FlushInstructionCache(GetCurrentProcess(), BNE_202_IDLE_CALL,
            sizeof(replacement));
    VirtualProtect(BNE_202_IDLE_CALL, sizeof(replacement), old_protection,
            &old_protection);
    trace_write("# bne-trace event=idle-hook-installed site=0x0040b058");
    return TRUE;
}

static BOOL install_projectile_hook(void) {
    static const BYTE expected[] = {0xe8, 0xf7, 0x5b, 0x00, 0x00};
    BYTE replacement[sizeof(expected)];
    int32_t old_relative;
    int32_t new_relative;
    DWORD old_protection;

    if (!executable_page_contains(BNE_202_PROJECTILE_CALL)
            || memcmp(BNE_202_PROJECTILE_CALL, expected,
                    sizeof(expected)) != 0) {
        trace_write("# bne-trace event=projectile-hook-rejected "
                "reason=signature");
        return FALSE;
    }
    memcpy(&old_relative, BNE_202_PROJECTILE_CALL + 1,
            sizeof(old_relative));
    original_projectile = (projectile_function) (void *)
            (BNE_202_PROJECTILE_CALL + sizeof(expected) + old_relative);
    if ((BYTE *) (void *) original_projectile != BNE_202_PROJECTILE_TARGET) {
        original_projectile = NULL;
        trace_write("# bne-trace event=projectile-hook-rejected "
                "reason=target");
        return FALSE;
    }
    replacement[0] = 0xe8;
    new_relative = (int32_t) ((BYTE *) (void *) traced_projectile
            - (BNE_202_PROJECTILE_CALL + sizeof(replacement)));
    memcpy(replacement + 1, &new_relative, sizeof(new_relative));
    if (!VirtualProtect(BNE_202_PROJECTILE_CALL, sizeof(replacement),
            PAGE_EXECUTE_READWRITE, &old_protection)) {
        trace_write("# bne-trace event=projectile-hook-rejected "
                "reason=virtual-protect");
        original_projectile = NULL;
        return FALSE;
    }
    memcpy(BNE_202_PROJECTILE_CALL, replacement, sizeof(replacement));
    FlushInstructionCache(GetCurrentProcess(), BNE_202_PROJECTILE_CALL,
            sizeof(replacement));
    VirtualProtect(BNE_202_PROJECTILE_CALL, sizeof(replacement),
            old_protection, &old_protection);
    trace_write("# bne-trace event=projectile-hook-installed "
            "site=0x00409f14 target=0x0040fb10");
    return TRUE;
}

static BOOL install_internal_order_hook(void) {
    static const BYTE expected[] = {0x83, 0xec, 0x08, 0x33, 0xc0};
    BYTE replacement[sizeof(expected)];
    BYTE *trampoline;
    int32_t relative;
    DWORD old_protection;
    char enabled[2];

    if (GetEnvironmentVariableA("CHONK_BNE_TRACE_INTERNAL_ORDERS", enabled,
            sizeof(enabled)) == 0) {
        return TRUE;
    }
    if (!executable_page_contains(BNE_202_INTERNAL_GIVE_ORDER)
            || memcmp(BNE_202_INTERNAL_GIVE_ORDER, expected,
                sizeof(expected)) != 0) {
        trace_write("# bne-trace event=internal-order-hook-rejected "
                "reason=signature");
        return FALSE;
    }
    trampoline = (BYTE *) VirtualAlloc(NULL, sizeof(expected) + 5,
            MEM_COMMIT | MEM_RESERVE, PAGE_EXECUTE_READWRITE);
    if (trampoline == NULL) {
        trace_write("# bne-trace event=internal-order-hook-rejected "
                "reason=allocation");
        return FALSE;
    }
    memcpy(trampoline, expected, sizeof(expected));
    trampoline[sizeof(expected)] = 0xe9;
    relative = (int32_t) ((BNE_202_INTERNAL_GIVE_ORDER + sizeof(expected))
            - (trampoline + sizeof(expected) + 5));
    memcpy(trampoline + sizeof(expected) + 1, &relative, sizeof(relative));
    original_internal_give_order =
            (give_order_function) (void *) trampoline;

    replacement[0] = 0xe9;
    relative = (int32_t) ((BYTE *) (void *) traced_internal_give_order
            - (BNE_202_INTERNAL_GIVE_ORDER + sizeof(replacement)));
    memcpy(replacement + 1, &relative, sizeof(relative));
    if (!VirtualProtect(BNE_202_INTERNAL_GIVE_ORDER, sizeof(replacement),
            PAGE_EXECUTE_READWRITE, &old_protection)) {
        original_internal_give_order = NULL;
        VirtualFree(trampoline, 0, MEM_RELEASE);
        trace_write("# bne-trace event=internal-order-hook-rejected "
                "reason=virtual-protect");
        return FALSE;
    }
    memcpy(BNE_202_INTERNAL_GIVE_ORDER, replacement, sizeof(replacement));
    FlushInstructionCache(GetCurrentProcess(), BNE_202_INTERNAL_GIVE_ORDER,
            sizeof(replacement));
    VirtualProtect(BNE_202_INTERNAL_GIVE_ORDER, sizeof(replacement),
            old_protection, &old_protection);
    trace_write("# bne-trace event=internal-order-hook-installed "
            "target=0x004513d0");
    return TRUE;
}

static BOOL install_ai_home_hook(void) {
    static const BYTE expected[] = {0x83, 0xec, 0x24, 0x33, 0xc0};
    BYTE replacement[sizeof(expected)];
    BYTE *trampoline;
    int32_t relative;
    DWORD old_protection;

    if (trace_unit_slot < 0) {
        return TRUE;
    }
    if (!executable_page_contains(BNE_202_AI_HOME_INIT)
            || memcmp(BNE_202_AI_HOME_INIT, expected, sizeof(expected)) != 0) {
        trace_write("# bne-trace event=ai-home-hook-rejected reason=signature");
        return FALSE;
    }
    trampoline = (BYTE *) VirtualAlloc(NULL, sizeof(expected) + 5,
            MEM_COMMIT | MEM_RESERVE, PAGE_EXECUTE_READWRITE);
    if (trampoline == NULL) {
        return FALSE;
    }
    memcpy(trampoline, expected, sizeof(expected));
    trampoline[sizeof(expected)] = 0xe9;
    relative = (int32_t) ((BNE_202_AI_HOME_INIT + sizeof(expected))
            - (trampoline + sizeof(expected) + 5));
    memcpy(trampoline + sizeof(expected) + 1, &relative, sizeof(relative));
    original_ai_home = (ai_home_function) (void *) trampoline;
    replacement[0] = 0xe9;
    relative = (int32_t) ((BYTE *) (void *) traced_ai_home
            - (BNE_202_AI_HOME_INIT + sizeof(replacement)));
    memcpy(replacement + 1, &relative, sizeof(relative));
    if (!VirtualProtect(BNE_202_AI_HOME_INIT, sizeof(replacement),
            PAGE_EXECUTE_READWRITE, &old_protection)) {
        original_ai_home = NULL;
        VirtualFree(trampoline, 0, MEM_RELEASE);
        return FALSE;
    }
    memcpy(BNE_202_AI_HOME_INIT, replacement, sizeof(replacement));
    FlushInstructionCache(GetCurrentProcess(), BNE_202_AI_HOME_INIT,
            sizeof(replacement));
    VirtualProtect(BNE_202_AI_HOME_INIT, sizeof(replacement), old_protection,
            &old_protection);
    trace_write("# bne-trace event=ai-home-hook-installed target=0x00427130");
    return TRUE;
}

static BOOL install_set_ai_behavior_hook(void) {
    static const BYTE expected[] = {0x83, 0xec, 0x08, 0x33, 0xc0};
    BYTE replacement[sizeof(expected)];
    BYTE *trampoline;
    int32_t relative;
    DWORD old_protection;

    if (trace_unit_slot < 0) {
        return TRUE;
    }
    if (!executable_page_contains(BNE_202_SET_AI_BEHAVIOR)
            || memcmp(BNE_202_SET_AI_BEHAVIOR, expected,
                sizeof(expected)) != 0) {
        trace_write("# bne-trace event=set-ai-behavior-hook-rejected "
                "reason=signature");
        return FALSE;
    }
    trampoline = (BYTE *) VirtualAlloc(NULL, sizeof(expected) + 5,
            MEM_COMMIT | MEM_RESERVE, PAGE_EXECUTE_READWRITE);
    if (trampoline == NULL) {
        return FALSE;
    }
    memcpy(trampoline, expected, sizeof(expected));
    trampoline[sizeof(expected)] = 0xe9;
    relative = (int32_t) ((BNE_202_SET_AI_BEHAVIOR + sizeof(expected))
            - (trampoline + sizeof(expected) + 5));
    memcpy(trampoline + sizeof(expected) + 1, &relative, sizeof(relative));
    original_set_ai_behavior =
            (set_ai_behavior_function) (void *) trampoline;
    replacement[0] = 0xe9;
    relative = (int32_t) ((BYTE *) (void *) traced_set_ai_behavior
            - (BNE_202_SET_AI_BEHAVIOR + sizeof(replacement)));
    memcpy(replacement + 1, &relative, sizeof(relative));
    if (!VirtualProtect(BNE_202_SET_AI_BEHAVIOR, sizeof(replacement),
            PAGE_EXECUTE_READWRITE, &old_protection)) {
        original_set_ai_behavior = NULL;
        VirtualFree(trampoline, 0, MEM_RELEASE);
        return FALSE;
    }
    memcpy(BNE_202_SET_AI_BEHAVIOR, replacement, sizeof(replacement));
    FlushInstructionCache(GetCurrentProcess(), BNE_202_SET_AI_BEHAVIOR,
            sizeof(replacement));
    VirtualProtect(BNE_202_SET_AI_BEHAVIOR, sizeof(replacement),
            old_protection, &old_protection);
    trace_write("# bne-trace event=set-ai-behavior-hook-installed "
            "target=0x004275b0");
    return TRUE;
}

static BOOL install_find_square_hook(void) {
    static const BYTE expected[] = {0x51, 0x8b, 0x54, 0x24, 0x08};
    BYTE replacement[sizeof(expected)];
    BYTE *trampoline;
    int32_t relative;
    DWORD old_protection;

    if (trace_unit_slot < 0) {
        return TRUE;
    }
    if (!executable_page_contains(BNE_202_FIND_NEARBY_SQUARE)
            || memcmp(BNE_202_FIND_NEARBY_SQUARE, expected,
                sizeof(expected)) != 0) {
        trace_write("# bne-trace event=find-square-hook-rejected reason=signature");
        return FALSE;
    }
    trampoline = (BYTE *) VirtualAlloc(NULL, sizeof(expected) + 5,
            MEM_COMMIT | MEM_RESERVE, PAGE_EXECUTE_READWRITE);
    if (trampoline == NULL) {
        return FALSE;
    }
    memcpy(trampoline, expected, sizeof(expected));
    trampoline[sizeof(expected)] = 0xe9;
    relative = (int32_t) ((BNE_202_FIND_NEARBY_SQUARE + sizeof(expected))
            - (trampoline + sizeof(expected) + 5));
    memcpy(trampoline + sizeof(expected) + 1, &relative, sizeof(relative));
    original_find_square = (find_square_function) (void *) trampoline;
    replacement[0] = 0xe9;
    relative = (int32_t) ((BYTE *) (void *) traced_find_square
            - (BNE_202_FIND_NEARBY_SQUARE + sizeof(replacement)));
    memcpy(replacement + 1, &relative, sizeof(relative));
    if (!VirtualProtect(BNE_202_FIND_NEARBY_SQUARE, sizeof(replacement),
            PAGE_EXECUTE_READWRITE, &old_protection)) {
        original_find_square = NULL;
        VirtualFree(trampoline, 0, MEM_RELEASE);
        return FALSE;
    }
    memcpy(BNE_202_FIND_NEARBY_SQUARE, replacement, sizeof(replacement));
    FlushInstructionCache(GetCurrentProcess(), BNE_202_FIND_NEARBY_SQUARE,
            sizeof(replacement));
    VirtualProtect(BNE_202_FIND_NEARBY_SQUARE, sizeof(replacement),
            old_protection, &old_protection);
    return TRUE;
}

static BOOL install_check_build_site_hook(void) {
    static const BYTE expected[] = {
        0x83, 0xec, 0x2c, 0x8b, 0x54, 0x24, 0x3c,
    };
    BYTE replacement[sizeof(expected)];
    BYTE *trampoline;
    int32_t relative;
    DWORD old_protection;

    if (trace_unit_slot < 0) {
        return TRUE;
    }
    if (!executable_page_contains(BNE_202_CHECK_BUILD_SITE)
            || memcmp(BNE_202_CHECK_BUILD_SITE, expected,
                sizeof(expected)) != 0) {
        trace_write("# bne-trace event=build-site-hook-rejected "
                "reason=signature");
        return FALSE;
    }
    trampoline = (BYTE *) VirtualAlloc(NULL, sizeof(expected) + 5,
            MEM_COMMIT | MEM_RESERVE, PAGE_EXECUTE_READWRITE);
    if (trampoline == NULL) {
        return FALSE;
    }
    memcpy(trampoline, expected, sizeof(expected));
    trampoline[sizeof(expected)] = 0xe9;
    relative = (int32_t) ((BNE_202_CHECK_BUILD_SITE + sizeof(expected))
            - (trampoline + sizeof(expected) + 5));
    memcpy(trampoline + sizeof(expected) + 1, &relative, sizeof(relative));
    original_check_build_site =
            (check_build_site_function) (void *) trampoline;
    memset(replacement, 0x90, sizeof(replacement));
    replacement[0] = 0xe9;
    relative = (int32_t) ((BYTE *) (void *) traced_check_build_site
            - (BNE_202_CHECK_BUILD_SITE + 5));
    memcpy(replacement + 1, &relative, sizeof(relative));
    if (!VirtualProtect(BNE_202_CHECK_BUILD_SITE, sizeof(replacement),
            PAGE_EXECUTE_READWRITE, &old_protection)) {
        original_check_build_site = NULL;
        VirtualFree(trampoline, 0, MEM_RELEASE);
        return FALSE;
    }
    memcpy(BNE_202_CHECK_BUILD_SITE, replacement, sizeof(replacement));
    FlushInstructionCache(GetCurrentProcess(), BNE_202_CHECK_BUILD_SITE,
            sizeof(replacement));
    VirtualProtect(BNE_202_CHECK_BUILD_SITE, sizeof(replacement),
            old_protection, &old_protection);
    trace_write("# bne-trace event=build-site-hook-installed "
            "target=0x00416c40");
    return TRUE;
}

static BOOL install_find_ai_wood_hook(void) {
    static const BYTE expected[] = {
        0x8b, 0x44, 0x24, 0x04, 0x56, 0x8b, 0x74, 0x24, 0x0c,
    };
    BYTE replacement[sizeof(expected)];
    BYTE *trampoline;
    int32_t relative;
    DWORD old_protection;

    if (trace_unit_slot < 0) {
        return TRUE;
    }
    if (!executable_page_contains(BNE_202_FIND_AI_WOOD)
            || memcmp(BNE_202_FIND_AI_WOOD, expected,
                sizeof(expected)) != 0) {
        trace_write("# bne-trace event=find-ai-wood-hook-rejected "
                "reason=signature");
        return FALSE;
    }
    trampoline = (BYTE *) VirtualAlloc(NULL, sizeof(expected) + 5,
            MEM_COMMIT | MEM_RESERVE, PAGE_EXECUTE_READWRITE);
    if (trampoline == NULL) {
        return FALSE;
    }
    memcpy(trampoline, expected, sizeof(expected));
    trampoline[sizeof(expected)] = 0xe9;
    relative = (int32_t) ((BNE_202_FIND_AI_WOOD + sizeof(expected))
            - (trampoline + sizeof(expected) + 5));
    memcpy(trampoline + sizeof(expected) + 1, &relative, sizeof(relative));
    original_find_ai_wood = (find_ai_wood_function) (void *) trampoline;
    memset(replacement, 0x90, sizeof(replacement));
    replacement[0] = 0xe9;
    relative = (int32_t) ((BYTE *) (void *) traced_find_ai_wood
            - (BNE_202_FIND_AI_WOOD + 5));
    memcpy(replacement + 1, &relative, sizeof(relative));
    if (!VirtualProtect(BNE_202_FIND_AI_WOOD, sizeof(replacement),
            PAGE_EXECUTE_READWRITE, &old_protection)) {
        original_find_ai_wood = NULL;
        VirtualFree(trampoline, 0, MEM_RELEASE);
        return FALSE;
    }
    memcpy(BNE_202_FIND_AI_WOOD, replacement, sizeof(replacement));
    FlushInstructionCache(GetCurrentProcess(), BNE_202_FIND_AI_WOOD,
            sizeof(replacement));
    VirtualProtect(BNE_202_FIND_AI_WOOD, sizeof(replacement),
            old_protection, &old_protection);
    trace_write("# bne-trace event=find-ai-wood-hook-installed "
            "target=0x0044e0f0");
    return TRUE;
}

static BOOL install_no_build_hook(BYTE *target, const BYTE *expected,
        size_t expected_bytes, void *replacement_function,
        no_build_function *original, const char *name) {
    BYTE replacement[16];
    BYTE *trampoline;
    int32_t relative;
    DWORD old_protection;

    if (!trace_no_build) {
        return TRUE;
    }
    if (expected_bytes < 5 || expected_bytes > sizeof(replacement)
            || !executable_page_contains(target)
            || memcmp(target, expected, expected_bytes) != 0) {
        trace_write("# bne-trace event=no-build-hook-rejected "
                "name=%s reason=signature", name);
        return FALSE;
    }
    trampoline = (BYTE *) VirtualAlloc(NULL, expected_bytes + 5,
            MEM_COMMIT | MEM_RESERVE, PAGE_EXECUTE_READWRITE);
    if (trampoline == NULL) {
        return FALSE;
    }
    memcpy(trampoline, expected, expected_bytes);
    trampoline[expected_bytes] = 0xe9;
    relative = (int32_t) ((target + expected_bytes)
            - (trampoline + expected_bytes + 5));
    memcpy(trampoline + expected_bytes + 1, &relative, sizeof(relative));
    *original = (no_build_function) (void *) trampoline;

    memset(replacement, 0x90, expected_bytes);
    replacement[0] = 0xe9;
    relative = (int32_t) ((BYTE *) replacement_function - (target + 5));
    memcpy(replacement + 1, &relative, sizeof(relative));
    if (!VirtualProtect(target, expected_bytes, PAGE_EXECUTE_READWRITE,
            &old_protection)) {
        *original = NULL;
        VirtualFree(trampoline, 0, MEM_RELEASE);
        return FALSE;
    }
    memcpy(target, replacement, expected_bytes);
    FlushInstructionCache(GetCurrentProcess(), target, expected_bytes);
    VirtualProtect(target, expected_bytes, old_protection, &old_protection);
    trace_write("# bne-trace event=no-build-hook-installed name=%s target=%p",
            name, target);
    return TRUE;
}

static void install_no_build_hooks(void) {
    static const BYTE set_expected[] = {
        0x51, 0x66, 0x0f, 0xb6, 0x4c, 0x24, 0x0c,
    };
    static const BYTE clear_expected[] = {
        0x51, 0x8b, 0x44, 0x24, 0x0c,
    };
    install_no_build_hook(BNE_202_SET_NO_BUILD, set_expected,
            sizeof(set_expected), (void *) traced_set_no_build,
            &original_set_no_build, "set");
    install_no_build_hook(BNE_202_CLEAR_NO_BUILD, clear_expected,
            sizeof(clear_expected), (void *) traced_clear_no_build,
            &original_clear_no_build, "clear");
}

static BOOL install_iat_hook(void **slot, void *replacement, void **original,
        const char *name) {
    MEMORY_BASIC_INFORMATION info;
    DWORD old_protection;
    HMODULE storm = GetModuleHandleA("Storm.dll");
    void *current;

    if (!executable_page_contains(slot) || storm == NULL) {
        trace_write("# bne-trace event=iat-hook-rejected name=%s reason=module",
                name);
        return FALSE;
    }
    current = *slot;
    if (VirtualQuery(current, &info, sizeof(info)) != sizeof(info)
            || info.AllocationBase != storm) {
        trace_write("# bne-trace event=iat-hook-rejected name=%s reason=target",
                name);
        return FALSE;
    }
    if (!VirtualProtect(slot, sizeof(*slot), PAGE_READWRITE, &old_protection)) {
        trace_write("# bne-trace event=iat-hook-rejected name=%s "
                "reason=virtual-protect error=%lu", name,
                (unsigned long) GetLastError());
        return FALSE;
    }
    *original = current;
    *slot = replacement;
    VirtualProtect(slot, sizeof(*slot), old_protection, &old_protection);
    trace_write("# bne-trace event=iat-hook-installed name=%s", name);
    return TRUE;
}

static void install_storm_trace_hooks(void) {
    install_iat_hook(BNE_202_STORM_OPEN_ARCHIVE_IAT,
            (void *) traced_storm_open_archive,
            (void **) &original_storm_open_archive, "SFileOpenArchive");
    install_iat_hook(BNE_202_STORM_OPEN_FILE_IAT,
            (void *) traced_storm_open_file,
            (void **) &original_storm_open_file, "SFileOpenFileEx");
}

__declspec(dllexport) DWORD WINAPI bne_trace_init(LPVOID unused) {
    BOOL hooked;
    BOOL bootstrap_hooked = TRUE;
    BOOL replay_hooked = TRUE;
    char cycles[32];
    DWORD cycles_length;
    char cd_fallback[8];
    DWORD cd_fallback_length;
    char tips[8];
    DWORD tips_length;
    char seed[32];
    DWORD seed_length;
    char ai_build_state[8];
    DWORD ai_build_state_length;
    char no_build[8];
    DWORD no_build_length;
    char trace_unit[32];
    DWORD trace_unit_length;
    char branch_cycle[32];
    DWORD branch_cycle_length;
    DWORD branch_ready_length;
    DWORD branch_resume_length;
    (void) unused;
    if (!trace_open()) {
        return 0;
    }
    if (!state_open()) {
        trace_close();
        return 0;
    }
    if (!read_replay_schedule()) {
        trace_close();
        return 0;
    }
    cycles_length = GetEnvironmentVariableA("CHONK_BNE_TRACE_CYCLES",
            cycles, sizeof(cycles));
    if (cycles_length > 0 && cycles_length < sizeof(cycles)) {
        unsigned long parsed = strtoul(cycles, NULL, 10);
        if (parsed > 0 && parsed <= 0x7fffffffUL) {
            trace_cycle_limit = (LONG) parsed;
        }
    }
    cd_fallback_length = GetEnvironmentVariableA(
            "CHONK_BNE_WINE_CD_FALLBACK", cd_fallback, sizeof(cd_fallback));
    wine_cd_fallback = cd_fallback_length == 1 && cd_fallback[0] == '1';
    tips_length = GetEnvironmentVariableA(
            "CHONK_BNE_DISABLE_STARTUP_TIPS", tips, sizeof(tips));
    disable_startup_tips = tips_length == 1 && tips[0] == '1';
    disable_tips_if_requested();
    seed_length = GetEnvironmentVariableA(
            "CHONK_BNE_SEED", seed, sizeof(seed));
    if (seed_length > 0 && seed_length < sizeof(seed)) {
        char *seed_end = NULL;
        unsigned long parsed_seed = strtoul(seed, &seed_end, 10);
        if (seed_end != seed && *seed_end == '\0') {
            deterministic_seed_enabled = TRUE;
            deterministic_seed = (DWORD) parsed_seed;
        }
    }
    ai_build_state_length = GetEnvironmentVariableA(
            "CHONK_BNE_TRACE_AI_BUILD_STATE", ai_build_state,
            sizeof(ai_build_state));
    trace_ai_build_state = ai_build_state_length == 1
            && ai_build_state[0] == '1';
    no_build_length = GetEnvironmentVariableA(
            "CHONK_BNE_TRACE_NO_BUILD", no_build, sizeof(no_build));
    trace_no_build = no_build_length == 1 && no_build[0] == '1';
    trace_unit_length = GetEnvironmentVariableA(
            "CHONK_BNE_TRACE_UNIT", trace_unit, sizeof(trace_unit));
    if (trace_unit_length > 0 && trace_unit_length < sizeof(trace_unit)) {
        char *trace_unit_end = NULL;
        unsigned long parsed_unit = strtoul(trace_unit, &trace_unit_end, 10);
        if (trace_unit_end != trace_unit && *trace_unit_end == '\0'
                && parsed_unit < BNE_UNIT_LIMIT) {
            trace_unit_slot = (LONG) parsed_unit;
        }
    }
    branch_cycle_length = GetEnvironmentVariableA(
            "CHONK_BNE_BRANCH_PAUSE_CYCLE", branch_cycle,
            sizeof(branch_cycle));
    branch_ready_length = GetEnvironmentVariableA(
            "CHONK_BNE_BRANCH_READY", branch_ready_path,
            sizeof(branch_ready_path));
    branch_resume_length = GetEnvironmentVariableA(
            "CHONK_BNE_BRANCH_RESUME", branch_resume_path,
            sizeof(branch_resume_path));
    if (branch_cycle_length > 0 && branch_cycle_length < sizeof(branch_cycle)) {
        char *branch_cycle_end = NULL;
        unsigned long parsed_cycle = strtoul(
                branch_cycle, &branch_cycle_end, 10);
        if (branch_cycle_end != branch_cycle && *branch_cycle_end == '\0'
                && parsed_cycle > 0 && parsed_cycle <= 0x7fffffffUL
                && branch_ready_length > 0
                && branch_ready_length < sizeof(branch_ready_path)
                && branch_resume_length > 0
                && branch_resume_length < sizeof(branch_resume_path)) {
            branch_pause_cycle = (LONG) parsed_cycle;
        }
    }
    command_file_valid = read_command_file();
    read_bootstrap_environment();
    install_storm_trace_hooks();
    install_sync_random_hook();
    install_async_random_hook();
    install_projectile_hook();
    install_idle_hook();
    install_internal_order_hook();
    install_ai_home_hook();
    install_set_ai_behavior_hook();
    install_find_square_hook();
    install_check_build_site_hook();
    install_find_ai_wood_hook();
    install_no_build_hooks();
    replay_hooked = install_sync_dispatch_hook();
    hooked = install_tick_hook();
    install_warmup_tick_hooks();
    if (bootstrap_pending != 0) {
        bootstrap_hooked = install_load_scenario_hook();
        if (bootstrap_hooked && deterministic_seed_enabled) {
            bootstrap_hooked = install_master_seed_hook();
        }
        if (bootstrap_hooked) {
            bootstrap_hooked = install_new_game_hook();
        }
        if (bootstrap_hooked) {
            bootstrap_hooked = install_main_state_hook();
        }
    }
    trace_write("# bne-trace event=initialized tick-hook=%s cycle-limit=%ld "
            "wine-cd-fallback=%s disable-startup-tips=%s bootstrap-hook=%s "
            "replay-dispatch-hook=%s",
            hooked ? "true" : "false", trace_cycle_limit,
            wine_cd_fallback ? "true" : "false",
            disable_startup_tips ? "true" : "false",
            bootstrap_hooked ? "true" : "false",
            replay_hooked ? "true" : "false");
    return hooked && bootstrap_hooked && replay_hooked
            && command_file_valid ? 3 : 1;
}

__declspec(dllexport) void w2p_init(void) {
    bne_trace_init(NULL);
}

__declspec(dllexport) void create_game(const char *name) {
    char escaped[512];
    json_escape(name == NULL ? "" : name, escaped, sizeof(escaped));
    trace_write("# bne-trace event=create-game name=\"%s\"", escaped);
}

__declspec(dllexport) void join_game(const char *name) {
    char escaped[512];
    json_escape(name == NULL ? "" : name, escaped, sizeof(escaped));
    trace_write("# bne-trace event=join-game name=\"%s\"", escaped);
}

__declspec(dllexport) void screen_update(unsigned int screen) {
    LONG callback = InterlockedIncrement(&screen_callbacks);
    if (callback == 1 || callback % 600 == 0) {
        trace_write("# bne-trace event=screen-update callback=%ld screen=%u",
                callback, screen);
    }
}

BOOL WINAPI DllMain(HINSTANCE instance, DWORD reason, LPVOID reserved) {
    (void) reserved;
    if (reason == DLL_PROCESS_ATTACH) {
        tracer_module = instance;
        DisableThreadLibraryCalls(instance);
    } else if (reason == DLL_PROCESS_DETACH) {
        trace_close();
    }
    return TRUE;
}
