import com.example.ai_develop.domain.AutonomousTaskJsonParsers
fun main() {
  val s = """{"success":true,"output":"EXEC_OUTPUT_STEP_INDEX_1","errors":null}"""
  println(AutonomousTaskJsonParsers.parseExecutionResult(s))
}
