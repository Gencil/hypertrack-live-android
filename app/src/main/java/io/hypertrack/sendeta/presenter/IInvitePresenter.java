package io.hypertrack.sendeta.presenter;

/**
 * Created by Aman on 19/07/17.
 */

public interface IInvitePresenter<V>  extends Presenter<V>{
    public void acceptInvite(String userID, String accountID, String previousUserId);
}
