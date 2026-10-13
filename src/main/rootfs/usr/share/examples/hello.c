/* Plain C works too:  cc hello.c -o hello && ./hello */
#include <stdio.h>

int main(int argc, char **argv) {
    printf("Hello from C on WebAssembly!\n");
    for (int i = 0; i < argc; ++i) printf("argv[%d] = %s\n", i, argv[i]);
    return 0;
}
