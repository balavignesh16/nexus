#include "../firmware/common/Logic.h"
#include <cassert>
int main() {
    usbv1::Living living;
    assert(!living.step(0,false,false));
    assert(living.step(1,true,false));
    assert(living.step(3000,false,false));
    assert(!living.step(3001,false,false));
    living.step(4000,false,true); assert(living.mode==0);
    living.step(4039,false,true); assert(living.mode==0);
    assert(living.step(4040,false,true)); assert(living.mode==1);
    living.step(4100,false,false); living.step(4140,false,false);
    living.step(4200,false,true); assert(!living.step(4240,true,true)); assert(living.mode==2);
    living.step(4300,false,false); living.step(4340,false,false);
    living.step(4400,false,true); assert(living.step(4440,true,true)); assert(living.mode==0);
    assert(usbv1::severity(0,299)==0); assert(usbv1::severity(0,300)==1);
    assert(usbv1::severity(1,200)==1); assert(usbv1::severity(1,199)==0);
    assert(usbv1::severity(1,600)==2); assert(usbv1::severity(2,500)==2);
    assert(usbv1::severity(2,499)==1); assert(usbv1::severity(2,100)==0);
    assert(usbv1::elapsed(10,0xfffffff0,26));
}
