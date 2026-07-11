#define _GNU_SOURCE

#include <errno.h>
#include <fcntl.h>
#include <libgen.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include <sys/syscall.h>
#include <unistd.h>

#ifndef RENAME_EXCHANGE
#define RENAME_EXCHANGE (1U << 1)
#endif

static int sync_regular_file(const char *path) {
    int fd = open(path, O_RDONLY | O_CLOEXEC | O_NOFOLLOW);
    if (fd < 0) return -1;
    int result = fsync(fd);
    int saved_errno = errno;
    close(fd);
    errno = saved_errno;
    return result;
}

static int sync_parent_directory(const char *path) {
    char *copy = strdup(path);
    if (copy == NULL) {
        errno = ENOMEM;
        return -1;
    }
    char *parent = dirname(copy);
    int fd = open(parent, O_RDONLY | O_CLOEXEC | O_DIRECTORY | O_NOFOLLOW);
    int result = fd < 0 ? -1 : fsync(fd);
    int saved_errno = errno;
    if (fd >= 0) close(fd);
    free(copy);
    errno = saved_errno;
    return result;
}

static int hex_value(char value) {
    if (value >= '0' && value <= '9') return value - '0';
    if (value >= 'a' && value <= 'f') return value - 'a' + 10;
    if (value >= 'A' && value <= 'F') return value - 'A' + 10;
    return -1;
}

static char *decode_hex_path(const char *encoded) {
    size_t length = strlen(encoded);
    if (length == 0 || (length & 1U) != 0) {
        errno = EINVAL;
        return NULL;
    }
    char *decoded = malloc(length / 2 + 1);
    if (decoded == NULL) {
        errno = ENOMEM;
        return NULL;
    }
    for (size_t index = 0; index < length; index += 2) {
        int high = hex_value(encoded[index]);
        int low = hex_value(encoded[index + 1]);
        if (high < 0 || low < 0) {
            free(decoded);
            errno = EINVAL;
            return NULL;
        }
        decoded[index / 2] = (char)((high << 4) | low);
    }
    decoded[length / 2] = '\0';
    return decoded;
}

int main(int argc, char **argv) {
    if (argc != 3) {
        fputs("usage: armusic-rename-exchange <left-utf8-hex> <right-utf8-hex>\n", stderr);
        return 64;
    }
    char *left_path = decode_hex_path(argv[1]);
    char *right_path = decode_hex_path(argv[2]);
    if (left_path == NULL || right_path == NULL) {
        free(left_path);
        free(right_path);
        perror("decode hex path");
        return 66;
    }

    struct stat left;
    struct stat right;
    if (lstat(left_path, &left) != 0 || lstat(right_path, &right) != 0) {
        const int saved_errno = errno;
        perror("lstat");
        free(left_path);
        free(right_path);
        return saved_errno > 0 && saved_errno < 126 ? saved_errno : 1;
    }
    if (!S_ISREG(left.st_mode) || !S_ISREG(right.st_mode)) {
        fputs("both exchange paths must be existing regular files\n", stderr);
        free(left_path);
        free(right_path);
        return 65;
    }
    if (left.st_dev != right.st_dev) {
        fputs("exchange paths are not on the same filesystem\n", stderr);
        free(left_path);
        free(right_path);
        return EXDEV;
    }
    if (sync_regular_file(left_path) != 0 || sync_regular_file(right_path) != 0) {
        const int saved_errno = errno;
        perror("fsync before exchange");
        free(left_path);
        free(right_path);
        return saved_errno > 0 && saved_errno < 126 ? saved_errno : 1;
    }

    if (syscall(__NR_renameat2, AT_FDCWD, left_path, AT_FDCWD, right_path, RENAME_EXCHANGE) != 0) {
        const int saved_errno = errno;
        errno = saved_errno;
        perror("renameat2(RENAME_EXCHANGE)");
        free(left_path);
        free(right_path);
        return saved_errno > 0 && saved_errno < 126 ? saved_errno : 1;
    }

    if (sync_regular_file(left_path) != 0 || sync_regular_file(right_path) != 0 ||
        sync_parent_directory(left_path) != 0 || sync_parent_directory(right_path) != 0) {
        const int saved_errno = errno;
        perror("fsync after exchange");
        free(left_path);
        free(right_path);
        return saved_errno > 0 && saved_errno < 126 ? saved_errno : 1;
    }
    free(left_path);
    free(right_path);
    return 0;
}
