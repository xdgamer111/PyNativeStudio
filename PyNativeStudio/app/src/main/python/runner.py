"""Execution bridge used by the isolated Android service."""
import builtins
import contextlib
import io
import os
import sys
import time
import traceback

_input_callback = None
_output_callback = None

class CallbackWriter(io.TextIOBase):
    def __init__(self, stream): self.stream = stream
    def write(self, text):
        if text and _output_callback:
            _output_callback.call(self.stream, str(text))
        return len(text or "")
    def flush(self): pass

def run_script(code, filename, output_callback, input_callback):
    global _input_callback, _output_callback
    _input_callback, _output_callback = input_callback, output_callback
    start = time.perf_counter()
    old_out, old_err, old_input = sys.stdout, sys.stderr, builtins.input
    namespace = {"__name__": "__main__", "__file__": filename, "__package__": None}
    def android_input(prompt=""):
        if prompt: output_callback.call("stdout", str(prompt))
        return str(input_callback.call())
    try:
        sys.stdout = CallbackWriter("stdout")
        sys.stderr = CallbackWriter("stderr")
        builtins.input = android_input
        compiled = compile(code, filename, "exec")
        exec(compiled, namespace, namespace)
        return (True, time.perf_counter() - start)
    except BaseException:
        output_callback.call("stderr", traceback.format_exc())
        return (False, time.perf_counter() - start)
    finally:
        sys.stdout, sys.stderr, builtins.input = old_out, old_err, old_input
        _input_callback = _output_callback = None

def syntax_check(code, filename="untitled.py"):
    try:
        compile(code, filename, "exec")
        return None
    except SyntaxError as exc:
        return {"line": exc.lineno or 1, "offset": exc.offset or 1, "message": exc.msg}
