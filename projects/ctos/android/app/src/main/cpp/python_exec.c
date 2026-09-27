#include <dlfcn.h>
#include <limits.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

int main(int argc, char **argv) {
    char library[PATH_MAX];
    ssize_t length = readlink("/proc/self/exe", library, sizeof(library) - 1);
    if (length <= 0) { perror("ctOS python3: executable path"); return 126; }
    library[length] = '\0';
    char *name = strrchr(library, '/');
    if (!name || (size_t) (name - library) + sizeof("/libpython3.13.so") > sizeof(library)) return 126;
    strcpy(name, "/libpython3.13.so");
    void *runtime = dlopen(library, RTLD_NOW | RTLD_GLOBAL);
    if (!runtime) { fprintf(stderr, "ctOS python3: %s\n", dlerror()); return 126; }
    int (*python_main)(int, char **) = (int (*)(int, char **)) dlsym(runtime, "Py_BytesMain");
    if (!python_main) { fprintf(stderr, "ctOS python3: %s\n", dlerror()); return 126; }
    return python_main(argc, argv);
}
