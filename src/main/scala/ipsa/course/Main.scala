package ipsa.course

import cats.Show
import cats.implicits.catsSyntaxEitherId
import enumeratum.values.{StringEnum, StringEnumEntry}
import ipsa.course.Main.AppEnv
import ipsa.course.OperatorService.OperatorService
import ipsa.course.TrainsService.TrainsService
import zio.console.{Console, getStrLn, putStrLn}
import zio.magic.ZioProvideMagicOps
import zio.{ExitCode, Has, RIO, Ref, Task, UIO, ULayer, URIO, ZIO, ZLayer}

import scala.io.Source

/**
  * 57. База даних роботи залізничного вокзалу
  * Програма-диспетчер, що управляє процесом
  * приходу та відправлення потягів,
  * реєструє аварійні ситуації,
  * видає інформацію користувачу про проведення посадки на потяги, стан потягів.
  */
// intentionally don't split and have all in one place
object Main extends zio.App {

  type AppEnv = Console with TrainsService with OperatorService
  private val appLayer = Console.live ++ OperatorService.live ++ TrainsService.live

  override def run(args: List[String]): URIO[zio.ZEnv, ExitCode] = {
    val program: URIO[AppEnv, Unit] = OperatorService.operatorLoop()
    program
      .inject(appLayer)
      .exitCode
  }
}

case class Train(number: String)
object Train {
  implicit val show: Show[Train] = (t: Train) => s"Train:[${t.number}]"
}

sealed trait TrainState
object TrainState {
  implicit val show: Show[TrainState] = {
    case OnTheRoad        => "[on the road]"
    case OnStation        => "[on station]"
    case AcceptPassengers => "[accept passengers]"
    case EmergencyState   => "[emergency]"
  }
}
case object OnTheRoad        extends TrainState
case object OnStation        extends TrainState
case object AcceptPassengers extends TrainState
case object EmergencyState   extends TrainState

object TrainsService {
  type TrainsService = Has[TrainsService.Service]
  trait Service {
    def printTrainStationState(): URIO[AppEnv, Unit]
    def printTrainsInState(state: TrainState): URIO[AppEnv, Unit]
    def trainState(trainNumber: String): URIO[AppEnv, Option[(Train, TrainState)]]
    def setTrainState(number: String, state: TrainState): RIO[AppEnv, Unit]
  }
  val live: ULayer[TrainsService] = ZLayer.fromManaged {
    for {
      trains <- fileLines("trains.txt").toManaged_.orDie
      ref <- Ref.makeManaged(
        trains
          .filter(_.trim.nonEmpty)
          .map(t => Train(t) -> OnStation)
          .toMap[Train, TrainState]
      )
    } yield new TrainsServiceLive(ref)
  }

  private def fileLines(filename: String): Task[Seq[String]] = {
    Task(Source.fromResource(filename))
      .bracket(s => UIO(s.close())) { source =>
        Task(source.getLines.toSeq)
      }
  }

  private class TrainsServiceLive(stateRef: Ref[Map[Train, TrainState]]) extends TrainsService.Service {
    override def printTrainStationState(): URIO[AppEnv, Unit] = {
      for {
        _     <- putStrLn("---- Train Station State")
        state <- stateRef.get
        _ <- ZIO.foreach_(state.toList.sortBy(_._1.number)) {
          case (train, state) =>
            putStrLn(
              s"${Show[Train].show(train)} - ${Show[TrainState].show(state)} "
            )
        }
      } yield ()
    }

    override def printTrainsInState(state: TrainState): URIO[AppEnv, Unit] = {
      for {
        _            <- putStrLn(s"---- Train Station trains in state ${Show[TrainState].show(state)}")
        stationState <- stateRef.get
        _ <- ZIO.foreach_(stationState.filter(_._2 == state).keys.toList.sortBy(_.number)) { train =>
          putStrLn(s"${Show[Train].show(train)}")
        }
      } yield ()
    }

    override def trainState(trainNumber: String): URIO[AppEnv, Option[(Train, TrainState)]] = {
      for {
        state <- stateRef.get
      } yield state.find(_._1.number == trainNumber)
    }

    def setTrainState(number: String, state: TrainState): RIO[AppEnv, Unit] = {
      for {
        updated <- stateRef.modify(stationState =>
          stationState.find(_._1.number == number) match {
            case Some(entry) => (true, stationState + (entry._1 -> state))
            case None        => (false, stationState)
          }
        )
        _ <- ZIO.fail[Throwable](new RuntimeException(s"train not found $number")).unless(updated)
      } yield ()
    }
  }
  def printTrainStationState(): URIO[AppEnv, Unit] =
    ZIO.accessM(_.get[TrainsService.Service].printTrainStationState())
  def printTrainsInState(state: TrainState): URIO[AppEnv, Unit] =
    ZIO.accessM(_.get[TrainsService.Service].printTrainsInState(state))
  def trainState(trainNumber: String): URIO[AppEnv, Option[(Train, TrainState)]] =
    ZIO.accessM(_.get[TrainsService.Service].trainState(trainNumber))
  def setTrainState(number: String, state: TrainState): RIO[AppEnv, Unit] =
    ZIO.accessM(_.get[TrainsService.Service].setTrainState(number, state))
}

object OperatorService {
  type OperatorService = Has[OperatorService.Service]
  trait Service {
    def operatorLoop(): URIO[AppEnv, Unit]
  }
  val live: ULayer[Has[OperatorService.Service]] =
    ZLayer.succeed(new OperatorServiceLive())

  private class OperatorServiceLive() extends OperatorService.Service {
    override def operatorLoop(): URIO[AppEnv, Unit] = {
      import OperatorCommand._
      for {
        _     <- printChoices()
        input <- getStrLn.orDie
        _ <- OperatorCommand.withValueOpt(input.trim) match {
          case Some(cmd) =>
            for {
              _ <- putStrLn(showAfterInputChoice.show(cmd))
              _ <- cmd match {
                case ShowTrainsStateCmd            => TrainsService.printTrainStationState()
                case ShowAcceptPassengersTrainsCmd => TrainsService.printTrainsInState(AcceptPassengers)
                case RegisterEmergencyCmd          => findAndSwitchStateProgram(EmergencyState)
                case ArriveCmd                     => findAndSwitchStateProgram(OnStation)
                case AcceptPassengersCmd           => findAndSwitchStateProgram(AcceptPassengers)
                case DepartCmd                     => findAndSwitchStateProgram(OnTheRoad)
              }
            } yield ()
          case None => putStrLn("not valid input")
        }
        _ <- operatorLoop()
      } yield ()
    }

    private def findAndSwitchStateProgram(state: TrainState): URIO[AppEnv, Unit] = {
      for {
        findRes <- findTrainProgram()
        _ <- findRes match {
          case Left(_) =>
            ZIO.unit
          case Right((train, trainState)) if trainState == state =>
            putStrLn(s"train ${Show[Train].show(train)} already in state ${Show[TrainState].show(state)}")
          case Right((train, _)) =>
            TrainsService.setTrainState(train.number, state).orDie *>
              putStrLn(s"train ${Show[Train].show(train)} moved to state ${Show[TrainState].show(state)}")
        }
      } yield ()
    }

    private def findTrainProgram(): URIO[AppEnv, Either[ExitCode, (Train, TrainState)]] = {
      for {
        _          <- putStrLn("Input train number")
        input      <- getStrLn.orDie
        trainState <- TrainsService.trainState(input.trim)
        res <- trainState match {
          case Some(trainState) => ZIO.succeed(trainState.asRight[ExitCode])
          case None =>
            def loop: URIO[AppEnv, Either[ExitCode, (Train, TrainState)]] = {
              for {
                _     <- putStrLn("Train not found, continue input?[y/n]")
                input <- getStrLn.orDie
                res <- input match {
                  case "y" => findTrainProgram()
                  case "n" => ZIO.succeed(ExitCode.success.asLeft[(Train, TrainState)])
                  case _   => putStrLn("not valid input") *> loop
                }
              } yield res
            }
            loop
        }
      } yield res
    }

    private def printChoices(): URIO[AppEnv, Unit] = {
      for {
        _ <- putStrLn("--------------------------")
        _ <- putStrLn("Input command number")
        _ <- ZIO.foreach_(OperatorCommand.values) { cmd =>
          putStrLn(OperatorCommand.showInputChoice.show(cmd))
        }
      } yield ()
    }

    sealed trait OperatorCommand extends StringEnumEntry
    object OperatorCommand extends StringEnum[OperatorCommand] {
      val values: IndexedSeq[OperatorCommand] = findValues

      case object ShowTrainsStateCmd            extends OperatorCommand { override val value: String = "0" }
      case object ShowAcceptPassengersTrainsCmd extends OperatorCommand { override val value: String = "1" }
      case object RegisterEmergencyCmd          extends OperatorCommand { override val value: String = "2" }
      case object ArriveCmd                     extends OperatorCommand { override val value: String = "3" }
      case object AcceptPassengersCmd           extends OperatorCommand { override val value: String = "4" }
      case object DepartCmd                     extends OperatorCommand { override val value: String = "5" }
      val showInputChoice: Show[OperatorCommand] = {
        case e @ ShowTrainsStateCmd            => s"[${e.value}] Show trains state"
        case e @ ShowAcceptPassengersTrainsCmd => s"[${e.value}] Show accept passengers trains"
        case e @ RegisterEmergencyCmd          => s"[${e.value}] Register emergency"
        case e @ ArriveCmd                     => s"[${e.value}] Confirm arrive"
        case e @ AcceptPassengersCmd           => s"[${e.value}] Allow accept passengers"
        case e @ DepartCmd                     => s"[${e.value}] Confirm depart"
      }
      val showAfterInputChoice: Show[OperatorCommand] = {
        case e @ ShowTrainsStateCmd            => s"Your input was [${e.value}] Show trains state"
        case e @ ShowAcceptPassengersTrainsCmd => s"Your input was [${e.value}] Show accept passengers trains"
        case e @ RegisterEmergencyCmd          => s"Your input was [${e.value}] Register emergency"
        case e @ ArriveCmd                     => s"Your input was [${e.value}] Confirm arrive"
        case e @ AcceptPassengersCmd           => s"Your input was [${e.value}] Allow accept passengers"
        case e @ DepartCmd                     => s"Your input was [${e.value}] Confirm depart"
      }
    }
  }

  def operatorLoop(): URIO[AppEnv, Unit] =
    ZIO.accessM(_.get[OperatorService.Service].operatorLoop())
}
