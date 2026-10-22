import os
import tempfile
import types
import unittest
from pathlib import Path
from unittest import mock

os.environ.setdefault("QT_QPA_PLATFORM","offscreen")
from PySide6.QtCore import Qt
from PySide6.QtWidgets import QApplication, QDialog, QListWidget, QPushButton
from cryptography.hazmat.primitives.asymmetric import rsa
from ipmsg_windows import app as client
from ipmsg_windows.protocol import fingerprint
from ipmsg_windows.store import Store


class UiTest(unittest.TestCase):
    def test_self_and_all_selection_survive_refresh_and_name_change(self):
        application=QApplication.instance() or QApplication([])
        key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
        self_id=fingerprint(key.public_key())
        stores=[]
        def make_store(path):
            value=Store(path);stores.append(value);return value
        def inspect():
            window=next(w for w in application.topLevelWidgets() if hasattr(w,"select_destination"))
            window.select_destination([self_id],"自分宛て");window.refresh()
            self.assertEqual([self_id],[i.data(Qt.UserRole) for i in window.peers.selectedItems()])
            window.toggle_trust()  # self is not a trust candidate
            window.device_name.setText("M6 UI test");window.save_name()
            self.assertIn("M6 UI test（自分）",window.peers.item(0).text())
            stores[0].discover("peer","Android","test-only","192.168.1.2",1)
            stores[0].trust("peer",True);window.refresh()
            all_button=next(b for b in window.findChildren(QPushButton) if "全端末（" in b.text())
            all_button.click();window.refresh()
            self.assertEqual({self_id,"peer"},{i.data(Qt.UserRole) for i in window.peers.selectedItems()})
            self.assertEqual([],stores[0].letters())  # selecting recipients never sends
            window.peers.item(0).setSelected(False)
            self.assertEqual(["peer"],[i.data(Qt.UserRole) for i in window.peers.selectedItems()])
            body="本文だけ\nUnicode🍎"
            window.body.setPlainText(body);window.send_to([self_id])
            def inspect_history():
                dialog=next(w for w in application.topLevelWidgets() if isinstance(w,QDialog))
                listing=dialog.findChild(QListWidget);listing.setCurrentRow(0)
                copy=next(b for b in dialog.findChildren(QPushButton) if b.text()=="本文をコピー")
                copy.click();self.assertEqual(body,application.clipboard().text())
                return 0
            with mock.patch.object(QDialog,"exec",side_effect=inspect_history):window.show_letters(True)
            window.deleteLater()
            return 0
        with tempfile.TemporaryDirectory() as folder, \
             mock.patch.object(client,"os",types.SimpleNamespace(name="nt",environ=os.environ)), \
             mock.patch("ipmsg_windows.identity.default_data_dir",return_value=Path(folder)), \
             mock.patch("ipmsg_windows.identity.load_or_create",return_value=key), \
             mock.patch("ipmsg_windows.network.LanNode.start"), \
             mock.patch("ipmsg_windows.network.LanNode.stop"), \
             mock.patch("ipmsg_windows.store.Store",side_effect=make_store), \
             mock.patch("PySide6.QtNetwork.QLocalSocket.waitForConnected",return_value=False), \
             mock.patch("PySide6.QtNetwork.QLocalServer.listen",return_value=True), \
             mock.patch.object(QApplication,"exec",side_effect=inspect), \
             mock.patch("sys.argv",["ipmsg"]):
            self.assertEqual(0,client.main())
            for value in stores:value.db.close()
