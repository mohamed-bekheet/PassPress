from PySide6.QtWidgets import QApplication

from .main_window import MainWindow


def run() -> int:
    app = QApplication([])
    app.setApplicationName("PASSPRESS HID Control Center")
    window = MainWindow()
    window.show()
    return app.exec()
