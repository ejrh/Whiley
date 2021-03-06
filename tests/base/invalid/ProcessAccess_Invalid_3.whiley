import * from whiley.lang.*

define MyProc as ref { int data }

int MyProc::copy(MyProc p):
    p.data = this.data // NOT OK

int MyProc::get():
    return this.data

MyProc System::create(int data):
    return new {data: data}

void ::main(System.Console sys):
    p1 = this.create(1)
    p2 = this.create(2)
    p1.copy(p2)
    sys.out.println(Any.toString(p1.get()))
    sys.out.println(Any.toString(p2.get()))

