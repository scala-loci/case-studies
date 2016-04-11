package chat

import util._

import retier._
import retier.architectures.P2PRegistry._
import retier.basicTransmitter._
import retier.serializable.upickle._
import retier.experimental.webrtc._

import scala.collection.mutable.Set
import scala.collection.mutable.Map
import scala.collection.mutable.WeakHashMap
import scala.util.Random
import scala.concurrent.ExecutionContext.Implicits.global


@multitier
object Application {
  trait Registry extends RegistryPeer[Node]
  trait Node extends NodePeer[Node, Registry]


  class NodeIndex {
    val nodes = WeakHashMap.empty[Remote[Node], Int]

    def getId(remote: Remote[Node]) =
      nodes getOrElseUpdate (remote, Random.nextInt)

    def getNode(id: Int) = nodes collectFirst {
      case (remote, nodeId) if nodeId == id => remote
    }
  }

  val nodeIndex = placed[Registry].local { implicit! => new NodeIndex }


  class ChatIndex {
    val connectors = Map.empty[Int, WebRTC.Connector]

    def getConnector(id: Int) = connectors get id

    def getConnectorOrElse(id: Int, connector: => WebRTC.Connector) =
      connectors getOrElse (id, connector)

    def insert(idConnector: (Int, WebRTC.Connector)) = connectors += idConnector

    def remove(node: Remote[Peer]) = getId(node) map { connectors -= _ }

    def getId(node: Remote[Peer]) = connectors collectFirst {
      case (id, connector) if node.protocol establishedBy connector => id
    }
  }

  val chatIndex = placed[Node].local { implicit! => new ChatIndex }


  def usersChanged = placed[Registry] { implicit! =>
    val (nodes, users) =
      (remote[Node].connected map { node =>
        node -> User(nodeIndex getId node, (name from node).asLocal_!)
      } sortBy { case (_, user) =>
        user.name
      }).unzip

    nodes.zipWithIndex foreach { case (node, index) =>
      remote.on(node) call updateUsers(users patch (index, Seq.empty, 1))
    }
  }

  placed[Registry] { implicit! =>
    remote[Node].left += { _ => usersChanged }
    remote[Node].joined += { _ => usersChanged }
  }

  val ui = placed[Node].local { implicit! => new UI }

  var name = placed[Node] { implicit! => ui.name.get }

  placed[Node] { implicit! =>
    ui.name addObserver { userName =>
      name = userName
      remote call usersChanged
    }
  }

  val selectedChatId = placed[Node].local { implicit! => Observable(Option.empty[Int]) }

  placed[Node].local { implicit! =>
    ui.chatRequested addObserver { user =>
      selectedChatId set Some(user.id)
    }

    ui.chatSelected addObserver { chat =>
      selectedChatId set Some(chat.id)
    }

    ui.chatClosed addObserver { chat =>
      if (selectedChatId.get contains chat.id)
        selectedChatId set None
    }
  }

  val messageSent = placed[Node].local { implicit! => Observable((0, "")) }

  val messageReceived = placed[Node].local { implicit! => Observable((0, "")) }

  def sendMessage(message: String) = placed[Node].local { implicit! =>
    selectedChatId.get foreach { selectedChatId =>
      val node = remote[Node].connected collectFirst {
        case node if chatIndex getId node contains selectedChatId => node
      }

      node foreach { node =>
        messageSent set ((selectedChatId, message))
        remote.on(node) call receiveMessage(message)
      }
    }
  }

  def receiveMessage(message: String) = placed[Node].issued { implicit! => node: Remote[Node] =>
    chatIndex getId node foreach { id =>
      messageReceived set ((id, message))
    }
  }

  def messageLog(id: Int) = placed[Node].local { implicit! =>
    val messageLog = Observable(Seq.empty[Message])

    messageSent addObserver { case (chatId, message) =>
      if (chatId == id)
        messageLog set (messageLog.get :+ Message(message, own = true))
    }

    messageReceived addObserver { case (chatId, message) =>
      if (chatId == id)
        messageLog set (messageLog.get :+ Message(message, own = false))
    }

    messageLog
  }

  def unreadMessageCount(id: Int) = placed[Node].local { implicit! =>
    val unreadMessageCount = Observable(0)

    selectedChatId addObserver { _ =>
      if (selectedChatId.get == Some(id))
        unreadMessageCount set 0
    }

    messageReceived addObserver { case (chatId, _) =>
      if (selectedChatId.get != Some(id) && chatId == id)
        unreadMessageCount set (unreadMessageCount.get + 1)
    }

    unreadMessageCount
  }

  val chats = placed[Node].local { implicit! => Observable(Seq.empty[ChatLog]) }

  placed[Node].local { implicit! =>
    remote[Node].left += { left =>
      chats set (chats.get filterNot { case ChatLog(node, _, _, _, _) => node == left })
    }

    remote[Node].joined += { node =>
      chatIndex getId node foreach { id =>
        val chatName = Observable("")
        (name from node).asLocal foreach { chatName set _ }

        val joined = ChatLog(node, id,
          chatName,
          unreadMessageCount(id),
          messageLog(id))

        chats set (chats.get :+ joined)
      }
    }
  }

  def updateUsers(users: Seq[User]) = placed[Node] { implicit! =>
    chats.get map { case ChatLog(_, chatId, chatName, _, _) =>
      users collectFirst { case User(id, name) if id == chatId =>
        chatName set name
      }
    }

    ui updateUsers users
  }


  placed[Node] { implicit! =>
    ui.chatClosed addObserver { case Chat(id, _, _, _) =>
      chats.get collectFirst { case ChatLog(node, `id`, _, _, _) => node.disconnect }
    }

    val updatingChats = Set.empty[ChatLog]

    def updateChats: Unit = {
      val updatedChats =
        chats.get map { case chat @ ChatLog(node, id, name, unread, _) =>
          if (!(updatingChats contains chat)) {
            updatingChats += chat
            name addObserver { _ => updateChats }
            unread addObserver { _ => updateChats }
          }
          Chat(id, name.get, unread.get, selectedChatId.get == Some(id))
        } sortBy { _.name }

      ui updateChats updatedChats
    }

    val updatingMessages = Set.empty[ChatLog]

    def updateMessages: Unit = {
      val updatedMessages =
        selectedChatId.get flatMap { id =>
          chats.get collectFirst { case chat @ ChatLog(_, `id`, _, _, chatLog) =>
            if (!(updatingMessages contains chat)) {
              updatingMessages += chat
              chatLog addObserver { _ => updateMessages }
            }
            chatLog.get
          }
        } getOrElse Seq.empty

      ui updateMessages updatedMessages
    }

    selectedChatId addObserver { _ =>
      updateChats
      updateMessages
    }

    chats addObserver { _ =>
      updateChats
      updateMessages
    }

    ui.messageSent addObserver { message =>
      sendMessage(message)

      if (selectedChatId.get.nonEmpty)
        ui.clearMessage
    }

    ui.chatRequested addObserver { user =>
      if ((chatIndex getConnector user.id).isEmpty) {
        val offer = WebRTC.offer() incremental propagateUpdate(user.id)
        chatIndex insert user.id -> offer
        remote[Node] connect offer
      }
    }

    remote[Node].left += chatIndex.remove
  }

  def propagateUpdate
      (requestedId: Int)
      (update: WebRTC.IncrementalUpdate): Unit localOn Node = placed { implicit! =>
    remote[Registry].issued.capture(requestedId, update) { implicit! => requesting: Remote[Node] =>
      (nodeIndex getNode requestedId) foreach { requested =>
        val requestingId = nodeIndex getId requesting

        remote.on(requested).capture(update, requestingId) { implicit! =>
          chatIndex getConnectorOrElse (requestingId, {
            val answer = WebRTC.answer() incremental propagateUpdate(requestingId)
            chatIndex insert requestingId -> answer
            remote[Node] connect answer
            answer
          }) use update
        }
      }
    }
  }
}