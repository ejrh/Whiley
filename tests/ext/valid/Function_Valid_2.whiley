import println from whiley.lang.System

define fr2nat as int where $ >= 0

string f(fr2nat x):
    return Any.toString(x)

void ::main(System.Console sys):
    y = 1
    sys.out.println(f(y))
