"""Simple letter-oriented PySide6 UI, tray receiver and optional user autostart."""
from __future__ import annotations

import argparse
import os
import sys

from . import __version__


def set_autostart(enabled: bool) -> None:
    if os.name != "nt": return
    import winreg
    command = f'"{sys.executable}"'
    with winreg.OpenKey(winreg.HKEY_CURRENT_USER, r"Software\Microsoft\Windows\CurrentVersion\Run",
                        0, winreg.KEY_SET_VALUE) as key:
        if enabled: winreg.SetValueEx(key, "XiaquiIPMessenger", 0, winreg.REG_SZ, command)
        else:
            try: winreg.DeleteValue(key, "XiaquiIPMessenger")
            except FileNotFoundError: pass


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", action="store_true")
    parser.add_argument("--diagnose", action="store_true")
    args = parser.parse_args()
    if args.version: print(__version__); return 0
    if args.diagnose:
        from .network import lan_broadcasts
        print("IP Messenger Windows", __version__, "UDP/TCP", 42124)
        print("broadcasts", ", ".join(sorted(lan_broadcasts())))
        return 0
    if os.name != "nt":
        print("The GUI is supported on Windows 11 only.", file=sys.stderr); return 2
    from PySide6.QtCore import QObject, QSettings, Qt, Signal
    from PySide6.QtNetwork import QLocalServer, QLocalSocket
    from PySide6.QtGui import QAction
    from PySide6.QtWidgets import (QApplication, QCheckBox, QDialog, QHBoxLayout, QLabel,
        QLineEdit, QListWidget, QListWidgetItem, QMainWindow, QMessageBox, QPushButton, QStyle,
        QSystemTrayIcon, QTextEdit, QVBoxLayout)
    from .identity import default_data_dir, load_or_create
    from .network import LanNode
    from .protocol import fingerprint
    from .store import Store

    app = QApplication(sys.argv); app.setQuitOnLastWindowClosed(False)
    instance_name = "Xiaqui-IPMessenger-v1"
    probe = QLocalSocket(); probe.connectToServer(instance_name)
    if probe.waitForConnected(250):
        probe.write(b"show"); probe.waitForBytesWritten(250); return 0
    instance_server = QLocalServer(app)
    if not instance_server.listen(instance_name):
        QMessageBox.critical(None,"IP Messenger","別のインスタンスが動作中か、二重起動防止用のローカル受口を作成できません")
        return 3
    data = default_data_dir(); private = load_or_create(data / "identity.dpapi")
    store = Store(data / "messages.sqlite3"); self_id = fingerprint(private.public_key())
    settings = QSettings("Xiaqui", "IPMessenger")

    class Bridge(QObject):
        changed = Signal()
        arrived = Signal(str, str)

    bridge = Bridge()

    class Window(QMainWindow):
        def __init__(self):
            super().__init__(); self.setWindowTitle("IP Messenger"); self.resize(520, 620)
            root = QVBoxLayout(); root.addWidget(QLabel("IP Messenger — LAN内の手紙"))
            root.addWidget(QLabel(f"この端末の鍵指紋（64桁）: {self_id}"))
            self.peers = QListWidget(); self.peers.setSelectionMode(QListWidget.MultiSelection); root.addWidget(self.peers)
            self.recipients = None  # None: selected peers, otherwise explicit destination IDs
            self.destination = QLabel("宛先: 選択した端末"); root.addWidget(self.destination)
            self.peers.itemSelectionChanged.connect(self.use_selected_peers)
            all_button = QPushButton("📢 全端末（自分を含む）"); all_button.setMinimumHeight(52)
            self_button = QPushButton("📝 自分宛て"); root.addWidget(all_button); root.addWidget(self_button)
            self.body = QTextEdit(); self.body.setPlaceholderText("本文（UTF-8で8192バイトまで）"); root.addWidget(self.body)
            send = QPushButton("選択した端末へ送る"); root.addWidget(send)
            row = QHBoxLayout(); inbox=QPushButton("受信箱"); outbox=QPushButton("送信履歴")
            approve=QPushButton("端末を承認 / 解除"); row.addWidget(inbox);row.addWidget(outbox);row.addWidget(approve);root.addLayout(row)
            self.device_name=QLineEdit(str(settings.value("deviceName",os.environ.get("COMPUTERNAME","Windows")))[:40])
            self.device_name.setPlaceholderText("この端末の名前");root.addWidget(self.device_name)
            save_name=QPushButton("端末名を保存");save_name.clicked.connect(self.save_name);root.addWidget(save_name)
            auto=QCheckBox("Windowsログイン時に起動（任意）"); auto.toggled.connect(set_autostart);root.addWidget(auto)
            shell=__import__("PySide6.QtWidgets",fromlist=["QWidget"]).QWidget();shell.setLayout(root);self.setCentralWidget(shell)
            all_button.clicked.connect(lambda:self.select_destination([p.peer_id for p in store.peers() if p.trusted]+[self_id], "全端末（自分を含む）"))
            self_button.clicked.connect(lambda:self.select_destination([self_id], "自分宛て"));send.clicked.connect(self.send_selected)
            approve.clicked.connect(self.toggle_trust);inbox.clicked.connect(lambda:self.show_letters(True));outbox.clicked.connect(lambda:self.show_letters(False))
            self.refresh()
        def refresh(self):
            # Rebuilding the list is not a user change of destination.
            selected_ids = {i.data(Qt.UserRole) for i in self.peers.selectedItems()}
            self.peers.blockSignals(True)
            try:
                self.peers.clear()
                for peer in store.peers():
                    state="ONLINE" if peer.online() else "OFFLINE"; trust="承認済" if peer.trusted else "未承認"
                    name="".join(ch if ch.isprintable() and ch not in "\r\n" else "�" for ch in peer.name)
                    item=QListWidgetItem(f"{name}  {state} / {trust}\n{peer.peer_id}");item.setData(Qt.UserRole,peer.peer_id)
                    self.peers.addItem(item)
                    if peer.peer_id in selected_ids: item.setSelected(True)
            finally:
                self.peers.blockSignals(False)
        def use_selected_peers(self):
            self.recipients = None
            self.destination.setText("宛先: 選択した端末")
        def select_destination(self, ids, title):
            self.recipients = ids
            self.destination.setText(f"宛先: {title}")
        def send_to(self, ids):
            text=self.body.toPlainText()
            try: store.enqueue(ids,self_id,node.name,text);self.body.clear();self.refresh()
            except ValueError as e: QMessageBox.warning(self,"送信できません",str(e))
        def send_selected(self):
            ids = self.recipients if self.recipients is not None else [i.data(Qt.UserRole) for i in self.peers.selectedItems()]
            self.send_to(ids)
        def toggle_trust(self):
            items=self.peers.selectedItems()
            if len(items)!=1: return
            peer=next(p for p in store.peers() if p.peer_id==items[0].data(Qt.UserRole))
            if peer.trusted:
                answer=QMessageBox.question(self,"承認解除",f"{peer.name} の承認を解除しますか？")
            else:
                answer=QMessageBox.question(self,"双方で鍵指紋を全文照合",
                    f"相手画面と64桁すべてが一致した場合だけ承認してください。\n\n{peer.peer_id}")
            if answer==QMessageBox.Yes: store.trust(peer.peer_id,not peer.trusted);self.refresh()
        def save_name(self):
            value=self.device_name.text().strip()[:40]
            if not value: QMessageBox.warning(self,"端末名","空の端末名は使えません");return
            settings.setValue("deviceName",value);node.name=value
        def show_letters(self,incoming):
            dialog=QDialog(self);dialog.setWindowTitle("受信箱" if incoming else "送信履歴");layout=QVBoxLayout(dialog)
            view=QTextEdit();view.setReadOnly(True);view.setPlainText("\n\n".join(f"{x.name} [{x.state}]\n{x.body}" for x in store.letters(incoming)));layout.addWidget(view);dialog.resize(480,500);dialog.exec()
        def closeEvent(self,event): event.ignore();self.hide();tray.showMessage("IP Messenger","受信を続けています")

    window=Window(); tray=QSystemTrayIcon(window);tray.setToolTip("IP Messenger")
    tray.setIcon(window.style().standardIcon(QStyle.SP_MessageBoxInformation))
    menu=__import__("PySide6.QtWidgets",fromlist=["QMenu"]).QMenu();show=QAction("開く");quit_action=QAction("終了")
    def reveal_window():
        window.showNormal(); window.raise_(); window.activateWindow()
    show.triggered.connect(reveal_window);quit_action.triggered.connect(app.quit);menu.addAction(show);menu.addAction(quit_action);tray.setContextMenu(menu);tray.show()
    tray.activated.connect(lambda reason: reveal_window() if reason == QSystemTrayIcon.Trigger else None)
    bridge.changed.connect(window.refresh)
    bridge.arrived.connect(lambda n,b:tray.showMessage(f"{n} から手紙",b[:120]))
    node=LanNode(store,private,str(settings.value("deviceName",os.environ.get("COMPUTERNAME","Windows")))[:40],
                 bridge.changed.emit,bridge.arrived.emit)
    def activate_existing():
        connection=instance_server.nextPendingConnection()
        if connection: connection.waitForReadyRead(100); connection.deleteLater()
        reveal_window()
    instance_server.newConnection.connect(activate_existing)
    try: node.start()
    except OSError as error:
        QMessageBox.critical(window,"LAN受信を開始できません",f"UDP/TCP 42124を確保できません。\n{error}")
        node.stop();return 4
    app.aboutToQuit.connect(node.stop);window.show();return app.exec()


if __name__ == "__main__": raise SystemExit(main())

