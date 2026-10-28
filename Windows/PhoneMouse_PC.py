"""PhoneMouse Windows receiver. Python 3.10+, standard library only."""
import ctypes
from ctypes import wintypes
import queue
import subprocess
import shutil
import os
import time
import secrets
import socket
import threading
import tkinter as tk
from tkinter import ttk

PORT = 45789
PIN = f"{secrets.randbelow(1_000_000):06d}"
user32 = ctypes.windll.user32

class MOUSEINPUT(ctypes.Structure):
    _fields_ = [('dx', wintypes.LONG), ('dy', wintypes.LONG), ('mouseData', wintypes.DWORD),
                ('dwFlags', wintypes.DWORD), ('time', wintypes.DWORD), ('dwExtraInfo', ctypes.c_size_t)]
class INPUTUNION(ctypes.Union):
    _fields_ = [('mi', MOUSEINPUT)]
class INPUT(ctypes.Structure):
    _fields_ = [('type', wintypes.DWORD), ('union', INPUTUNION)]

user32.SendInput.argtypes = (wintypes.UINT, ctypes.POINTER(INPUT), ctypes.c_int)
user32.SendInput.restype = wintypes.UINT

DOWN = {1: 0x0002, 2: 0x0008, 3: 0x0020}
UP = {1: 0x0004, 2: 0x0010, 3: 0x0040}

def mouse(flags, dx=0, dy=0, wheel=0):
    mi = MOUSEINPUT(dx=dx,dy=dy,mouseData=wheel & 0xffffffff, dwFlags=flags,time=0,dwExtraInfo=0)
    packet = INPUT(type=0,union=INPUTUNION(mi=mi))
    user32.SendInput(1,ctypes.byref(packet),ctypes.sizeof(INPUT))

class Receiver:
    def __init__(self):
        self.root=tk.Tk(); self.root.title('PhoneMouse — Windows');self.root.geometry('480x365')
        self.root.configure(bg='#161c29'); self.root.resizable(False,False)
        self.events=queue.Queue();self.running=True;self.sock=None
        style={'bg':'#161c29','fg':'#edf3fa'}
        tk.Label(self.root,text='PhoneMouse',font=('Segoe UI',23,'bold'),**style).pack(pady=(20,5))
        tk.Label(self.root,text='USB • ADB reverse • без Wi-Fi',font=('Segoe UI',13),**style).pack(pady=4)
        tk.Label(self.root,text='Порт: 45789',font=('Segoe UI',11),**style).pack()
        tk.Label(self.root,text='PIN для телефона',font=('Segoe UI',11),**style).pack(pady=(14,0))
        tk.Label(self.root,text=PIN,font=('Consolas',29,'bold'),bg='#161c29',fg='#6ad7ed').pack()
        self.status=tk.StringVar(value='Ожидание подключения...')
        tk.Label(self.root,textvariable=self.status,font=('Segoe UI',11),wraplength=390,**style).pack(pady=10)
        tk.Label(self.root,text='Подключи телефон кабелем и разреши USB-отладку.\nПрограмма настроит перенаправление порта автоматически.',font=('Segoe UI',10),**style).pack()
        tk.Button(self.root,text='Выход',command=self.close).pack(pady=14)
        self.root.protocol('WM_DELETE_WINDOW',self.close)
        threading.Thread(target=self.serve,daemon=True).start()
        threading.Thread(target=self.configure_adb,daemon=True).start()
        self.root.after(100,self.pump)
    def pump(self):
        try:
            while True:self.status.set(self.events.get_nowait())
        except queue.Empty:pass
        if self.running:self.root.after(100,self.pump)
    def announce(self,msg):self.events.put(msg)
    def adb_path(self):
        local=os.path.join(os.path.dirname(os.path.abspath(__file__)), 'platform-tools', 'adb.exe')
        if os.path.isfile(local):return local
        local=os.path.join(os.path.dirname(os.path.abspath(__file__)), 'adb.exe')
        if os.path.isfile(local):return local
        return shutil.which('adb')
    def configure_adb(self):
        adb=self.adb_path()
        if not adb:
            self.announce('ADB не найден. Распакуй platform-tools в папку Windows\\platform-tools')
            return
        self.announce('Ожидание телефона с USB-отладкой...')
        last_state=None
        while self.running:
            try:
                proc=subprocess.run([adb,'devices'],capture_output=True,text=True,timeout=10,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
                lines=proc.stdout.splitlines()[1:]
                authorized=[x.split()[0] for x in lines if x.strip().endswith('\tdevice')]
                unauthorized=any('unauthorized' in x for x in lines)
                if authorized:
                    dev=authorized[0]
                    result=subprocess.run([adb,'-s',dev,'reverse','tcp:45789','tcp:45789'],capture_output=True,text=True,timeout=10,creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
                    state=('ready',dev) if result.returncode==0 else ('error',result.stderr.strip())
                    if state!=last_state:
                        self.announce('USB подключён. Введи PIN на телефоне.' if state[0]=='ready' else 'ADB reverse не удалось: '+state[1])
                        last_state=state
                else:
                    state=('unauthorized' if unauthorized else 'missing', '')
                    if state!=last_state:
                        self.announce('Подтверди USB-отладку на телефоне' if unauthorized else 'Ожидание телефона по USB...')
                        last_state=state
            except (OSError,subprocess.TimeoutExpired) as exc:
                self.announce('Ошибка ADB: '+str(exc))
            for _ in range(20):
                if not self.running:break
                time.sleep(0.25)
    def serve(self):
        try:
            listener=socket.socket(socket.AF_INET,socket.SOCK_STREAM)
            listener.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
            listener.bind(('127.0.0.1',PORT));listener.listen(2);listener.settimeout(1)
            self.sock=listener
        except OSError as e:
            self.announce('Невозможно открыть порт: '+str(e));return
        while self.running:
            try:conn,addr=listener.accept()
            except socket.timeout:continue
            except OSError:break
            with conn:
                try:self.handle(conn,addr)
                except (OSError,UnicodeError,ValueError) as e:self.announce('Соединение прервано: '+str(e))
        listener.close()
    def handle(self,conn,addr):
        conn.settimeout(5)
        stream=conn.makefile('r',encoding='ascii',errors='replace',newline='\n')
        hello=stream.readline(80).strip()
        if hello != 'HELLO '+PIN:
            conn.sendall(b'DENIED\n');self.announce('Отклонено подключение с '+addr[0]);return
        conn.sendall(b'OK\n');conn.settimeout(None)
        self.announce('Подключен телефон: '+addr[0])
        held=set()
        try:
            for line in stream:
                parts=line.strip().split()
                if not parts:continue
                try:
                    if parts[0]=='M' and len(parts)==3:
                        dx,dy=map(int,parts[1:]);dx=max(-100,min(100,dx));dy=max(-100,min(100,dy))
                        if dx or dy:mouse(0x0001,dx,dy)
                    elif parts[0]=='W' and len(parts)==2:
                        ticks=max(-12,min(12,int(parts[1])));
                        if ticks:mouse(0x0800,wheel=ticks*120)
                    elif parts[0]=='B' and len(parts)==3:
                        button=int(parts[1]); pressed=int(parts[2]);
                        if button not in DOWN or pressed not in (0,1):continue
                        if pressed and button not in held:mouse(DOWN[button]);held.add(button)
                        elif not pressed and button in held:mouse(UP[button]);held.discard(button)
                except (ValueError,OverflowError):continue
        finally:
            for button in held:mouse(UP[button])
            self.announce('Телефон отключён. Ожидание...')
    def close(self):
        self.running=False
        if self.sock:
            try:self.sock.close()
            except OSError:pass
        self.root.destroy()

if __name__=='__main__':
    import platform
    if platform.system()!='Windows':raise SystemExit('Требуется Windows')
    Receiver().root.mainloop()
