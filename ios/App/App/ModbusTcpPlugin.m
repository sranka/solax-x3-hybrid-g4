#import <Capacitor/Capacitor.h>

CAP_PLUGIN(ModbusTcpPlugin, "ModbusTcp",
    CAP_PLUGIN_METHOD(readRegisters, CAPPluginReturnPromise);
)
