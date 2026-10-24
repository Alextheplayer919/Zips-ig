#pragma once
#include <unistd.h>

namespace clientbase {
struct ProcessIdentity { pid_t pid; uid_t uid; };
inline ProcessIdentity current_identity() { return {getpid(), getuid()}; }
}
