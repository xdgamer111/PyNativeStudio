"""Execution bridge used by the isolated Android service."""

import builtins
import io
import sys
import time
import traceback

_input_callback = None
_output_callback = None


class CallbackWriter(io.TextIOBase):
    """Forwards Python stdout and stderr to a Kotlin callback."""

    def __init__(self, stream):
        super().__init__()
        self.stream = stream

    def write(self, text):
        if text and _output_callback:
            _output_callback.call(self.stream, str(text))
        return len(text or "")

    def flush(self):
        return None


def run_script(code, filename, output_callback, input_callback):
    """Compile and execute one Python script and return success and runtime."""
    global _input_callback, _output_callback

    _input_callback = input_callback
    _output_callback = output_callback

    start = time.perf_counter()
    old_stdout = sys.stdout
    old_stderr = sys.stderr
    old_input = builtins.input

    namespace = {
        "__name__": "__main__",
        "__file__": filename,
        "__package__": None,
    }

    def android_input(prompt=""):
        if prompt:
            output_callback.call("stdout", str(prompt))
        return str(input_callback.call())

    try:
        sys.stdout = CallbackWriter("stdout")
        sys.stderr = CallbackWriter("stderr")
        builtins.input = android_input

        compiled = compile(code, filename, "exec")
        exec(compiled, namespace, namespace)
        return True, time.perf_counter() - start
    except BaseException:
        output_callback.call("stderr", traceback.format_exc())
        return False, time.perf_counter() - start
    finally:
        sys.stdout = old_stdout
        sys.stderr = old_stderr
        builtins.input = old_input
        _input_callback = None
        _output_callback = None


def syntax_check(code, filename="untitled.py"):
    """Return syntax-error details or None when the code is valid."""
    try:
        compile(code, filename, "exec")
        return None
    except SyntaxError as error:
        return {
            "line": error.lineno or 1,
            "offset": error.offset or 1,
            "message": error.msg,
        }
