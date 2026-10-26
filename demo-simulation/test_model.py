import unittest
from server import Model

class ModelTests(unittest.TestCase):
    def test_pir_hold_and_button_modes(self):
        m=Model();m.inputs('ESP1',{'motion':True});m.step(.1);self.assertTrue(m.led1)
        m.inputs('ESP1',{'motion':False});m.step(2.9);self.assertTrue(m.led1)
        m.step(.2);self.assertFalse(m.led1)
        m.inputs('ESP1',{'button':True});m.inputs('ESP1',{'button':True});self.assertEqual(m.mode,1)
        m.inputs('ESP1',{'button':False});m.inputs('ESP1',{'button':True});self.assertEqual(m.mode,2)
    def test_sensor_response_bounded_and_gradual(self):
        m=Model();m.inputs('ESP1',{'temperature':40});m.step(1)
        self.assertTrue(25<m.temperature<40)
        with self.assertRaises(ValueError):m.inputs('ESP1',{'temperature':float('nan')})
        with self.assertRaises(ValueError):m.inputs('ESP3',{'led':True})
        with self.assertRaises(ValueError):m.inputs('ESP2',{'gas':5000})
    def test_gas_hysteresis_and_alarm_cannot_be_muted(self):
        m=Model();m.gas=1000;m.targets['gas']=1000;m.step(.1);self.assertEqual(m.severity,2)
        m.command('ESP2','BUZZER',False);self.assertTrue(m.readings()['ESP2']['demo_buzzer_state'])
        m.gas=750;m.targets['gas']=750;m.step(.1);self.assertEqual(m.severity,2)
        m.gas=650;m.targets['gas']=650;m.step(.1);self.assertEqual(m.severity,1)
        m.gas=300;m.targets['gas']=300;m.step(.1);self.assertEqual(m.severity,0)
    def test_servo_slew_and_command_types(self):
        m=Model();m.command('ESP3','SERVO',120);m.step(.1);self.assertEqual(m.servo,96)
        m.step(2);self.assertEqual(m.servo,120)
        for value in (True,-1,181,'120'):
            with self.assertRaises(ValueError):m.command('ESP3','SERVO',value)
    def test_buzzer_override_expiry(self):
        m=Model();m.command('ESP2','BUZZER',True);self.assertTrue(m.readings()['ESP2']['demo_buzzer_state'])
        m.step(10.1);self.assertFalse(m.readings()['ESP2']['demo_buzzer_state'])

if __name__=='__main__':unittest.main()
